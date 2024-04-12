/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.druid.query.filter;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.RangeSet;
import com.google.common.primitives.Chars;
import org.apache.druid.common.config.NullHandling;
import org.apache.druid.java.util.common.StringUtils;
import org.apache.druid.query.extraction.ExtractionFn;
import org.apache.druid.segment.filter.LikeFilter;

import javax.annotation.Nullable;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public class LikeDimFilter extends AbstractOptimizableDimFilter implements DimFilter
{
  private final String dimension;
  private final String pattern;
  @Nullable
  private final Character escapeChar;
  @Nullable
  private final ExtractionFn extractionFn;
  @Nullable
  private final FilterTuning filterTuning;
  private final LikeMatcher likeMatcher;

  @JsonCreator
  public LikeDimFilter(
      @JsonProperty("dimension") final String dimension,
      @JsonProperty("pattern") final String pattern,
      @JsonProperty("escape") @Nullable final String escape,
      @JsonProperty("extractionFn") @Nullable final ExtractionFn extractionFn,
      @JsonProperty("filterTuning") @Nullable final FilterTuning filterTuning
  )
  {
    this.dimension = Preconditions.checkNotNull(dimension, "dimension");
    this.pattern = Preconditions.checkNotNull(pattern, "pattern");
    this.extractionFn = extractionFn;
    this.filterTuning = filterTuning;

    if (escape != null && escape.length() != 1) {
      throw new IllegalArgumentException("Escape must be null or a single character");
    } else {
      this.escapeChar = escape == null ? null : escape.charAt(0);
    }

    this.likeMatcher = LikeMatcher.from(pattern, this.escapeChar);
  }

  @VisibleForTesting
  public LikeDimFilter(
      final String dimension,
      final String pattern,
      @Nullable final String escape,
      @Nullable final ExtractionFn extractionFn
  )
  {
    this(dimension, pattern, escape, extractionFn, null);
  }

  @JsonProperty
  public String getDimension()
  {
    return dimension;
  }

  @JsonProperty
  public String getPattern()
  {
    return pattern;
  }

  @Nullable
  @JsonProperty
  public String getEscape()
  {
    return escapeChar != null ? escapeChar.toString() : null;
  }

  @Nullable
  @JsonProperty
  public ExtractionFn getExtractionFn()
  {
    return extractionFn;
  }

  @Nullable
  @JsonInclude(JsonInclude.Include.NON_NULL)
  @JsonProperty
  public FilterTuning getFilterTuning()
  {
    return filterTuning;
  }

  @Override
  public byte[] getCacheKey()
  {
    final byte[] dimensionBytes = StringUtils.toUtf8(dimension);
    final byte[] patternBytes = StringUtils.toUtf8(pattern);
    final byte[] escapeBytes = escapeChar == null ? new byte[0] : Chars.toByteArray(escapeChar);
    final byte[] extractionFnBytes = extractionFn == null ? new byte[0] : extractionFn.getCacheKey();
    final int sz = 4 + dimensionBytes.length + patternBytes.length + escapeBytes.length + extractionFnBytes.length;
    return ByteBuffer.allocate(sz)
                     .put(DimFilterUtils.LIKE_CACHE_ID)
                     .put(dimensionBytes)
                     .put(DimFilterUtils.STRING_SEPARATOR)
                     .put(patternBytes)
                     .put(DimFilterUtils.STRING_SEPARATOR)
                     .put(escapeBytes)
                     .put(DimFilterUtils.STRING_SEPARATOR)
                     .put(extractionFnBytes)
                     .array();
  }

  @Override
  public Filter toFilter()
  {
    return new LikeFilter(dimension, extractionFn, likeMatcher, filterTuning);
  }

  @Override
  public RangeSet<String> getDimensionRangeSet(String dimension)
  {
    return null;
  }

  @Override
  public Set<String> getRequiredColumns()
  {
    return ImmutableSet.of(dimension);
  }

  @Override
  public boolean equals(Object o)
  {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    LikeDimFilter that = (LikeDimFilter) o;
    return dimension.equals(that.dimension) &&
           pattern.equals(that.pattern) &&
           Objects.equals(escapeChar, that.escapeChar) &&
           Objects.equals(extractionFn, that.extractionFn) &&
           Objects.equals(filterTuning, that.filterTuning);
  }

  @Override
  public int hashCode()
  {
    return Objects.hash(dimension, pattern, escapeChar, extractionFn, filterTuning);
  }

  @Override
  public String toString()
  {
    final DimFilterToStringBuilder builder = new DimFilterToStringBuilder();
    builder.appendDimension(dimension, extractionFn).append(" LIKE '").append(pattern).append("'");
    if (escapeChar != null) {
      builder.append(" ESCAPE '").append(escapeChar).append("'");
    }
    return builder.appendFilterTuning(filterTuning).build();
  }

  public static class LikeMatcher
  {
    public enum SuffixMatch
    {
      MATCH_ANY,
      MATCH_EMPTY,
      MATCH_PATTERN
    }

    // Strings match if:
    //  (a) suffixMatch is MATCH_ANY and they start with "prefix"
    //  (b) suffixMatch is MATCH_EMPTY and they start with "prefix" and contain nothing after prefix
    //  (c) suffixMatch is MATCH_PATTERN and the string matches "pattern"
    private final SuffixMatch suffixMatch;

    // Prefix that matching strings are known to start with. May be empty.
    private final String prefix;

    // Pattern that describes matching strings.
    private final List<LikePattern> pattern;

    private final String likePattern;

    private LikeMatcher(
        final String likePattern,
        final SuffixMatch suffixMatch,
        final String prefix,
        final List<LikePattern> pattern
    )
    {
      this.likePattern = likePattern;
      this.suffixMatch = Preconditions.checkNotNull(suffixMatch, "suffixMatch");
      this.prefix = NullHandling.nullToEmptyIfNeeded(prefix);
      this.pattern = Preconditions.checkNotNull(pattern, "pattern");
    }

    public static LikeMatcher from(final String likePattern, @Nullable final Character escapeChar)
    {
      final StringBuilder prefix = new StringBuilder();
      // The goal is to normalize the pattern so that contiguous sequences of % and/or _ have all the %'s first, e.g.:
      // x_%_%yz = x%__yz
      // Each of the pieces separated by %'s are further split into pattern parts, with each part have zero or more
      // required leading characters (the _'s) followed by a string literal, e.g.:
      // a_b%c%__d_e -> [[a, _b], [c], [__d, _e]]
      final List<LikePattern> pattern = new ArrayList<>();
      final StringBuilder value = new StringBuilder(); // literals we've seen since the last _ or %
      List<LikePattern.PatternPart> parts = new ArrayList<>();
      boolean anchored = true; // false if the pending part is prefixed with the % wildcard
      int leadingLength = 0; // how many _'s we've seen since the last literal
      boolean escaping = false;
      boolean inPrefix = true;
      SuffixMatch suffixMatch = SuffixMatch.MATCH_EMPTY;
      for (int i = 0; i < likePattern.length(); i++) {
        final char c = likePattern.charAt(i);
        if (escapeChar != null && c == escapeChar && !escaping) {
          escaping = true;
        } else if (c == '%' && !escaping) {
          inPrefix = false;
          if (suffixMatch == SuffixMatch.MATCH_EMPTY) {
            suffixMatch = SuffixMatch.MATCH_ANY;
          }
          if (value.length() > 0 || !parts.isEmpty()) {
            parts.add(new LikePattern.PatternPart(leadingLength, value.toString()));
            pattern.add(new LikePattern(anchored, parts.toArray(new LikePattern.PatternPart[0])));
            parts.clear();
            value.setLength(0);
            leadingLength = 0;
          }
          anchored = false;
        } else if (c == '_' && !escaping) {
          inPrefix = false;
          suffixMatch = SuffixMatch.MATCH_PATTERN;
          if (value.length() > 0) {
            parts.add(new LikePattern.PatternPart(leadingLength, value.toString()));
            value.setLength(0);
            leadingLength = 0;
          }
          ++leadingLength;
        } else {
          if (inPrefix) {
            prefix.append(c);
          } else {
            suffixMatch = SuffixMatch.MATCH_PATTERN;
          }
          value.append(c);
          escaping = false;
        }
      }

      if (value.length() > 0 || leadingLength > 0 || !anchored || !parts.isEmpty()) {
        parts.add(new LikePattern.PatternPart(leadingLength, value.toString()));
        pattern.add(new LikePattern(anchored, parts.toArray(new LikePattern.PatternPart[0])));
      }

      return new LikeMatcher(likePattern, suffixMatch, prefix.toString(), pattern);
    }

    public DruidPredicateMatch matches(@Nullable final String s)
    {
      return matches(s, pattern);
    }

    private static DruidPredicateMatch matches(@Nullable final String s, List<LikePattern> pattern)
    {
      String val = NullHandling.nullToEmptyIfNeeded(s);
      if (val == null) {
        return DruidPredicateMatch.UNKNOWN;
      }

      if (pattern.isEmpty()) {
        return DruidPredicateMatch.of(val.isEmpty());
      }

      // Check for suffix anchored to the end of the string, e.g., a%b_d_
      // (We can't eagerly match the b_d_ portion, since that leads to false negatives: abcdexyzbcde)
      // Note: In the case of a trailing %, the anchored suffix is an empty string.
      LikePattern suffix = pattern.get(pattern.size() - 1);
      int suffixOffset = val.length() - suffix.length;

      if (!suffix.matchesSuffix(val)) {
        return DruidPredicateMatch.FALSE;
      } else if (suffix.anchored) {
        // There was no % in the pattern
        return DruidPredicateMatch.of(suffixOffset == 0);
      }

      int offset = 0;

      for (int i = 0; i < pattern.size() - 1; ++i) {
        offset = pattern.get(i).advance(val, offset, val.length());

        if (offset == -1) {
          return DruidPredicateMatch.FALSE;
        }
      }

      return DruidPredicateMatch.TRUE;
    }

    /**
     * Checks if the suffix of "value" matches the suffix of this matcher. The first prefix.length() characters
     * of "value" are ignored. This method is useful if you've already independently verified the prefix.
     */
    public DruidPredicateMatch matchesSuffixOnly(@Nullable String value)
    {
      if (suffixMatch == SuffixMatch.MATCH_ANY) {
        return DruidPredicateMatch.TRUE;
      } else if (suffixMatch == SuffixMatch.MATCH_EMPTY) {
        return value == null ? matches(null) : DruidPredicateMatch.of(value.length() == prefix.length());
      } else {
        // suffixMatch is MATCH_PATTERN
        return matches(value);
      }
    }

    public DruidPredicateFactory predicateFactory(final ExtractionFn extractionFn)
    {
      return new PatternDruidPredicateFactory(extractionFn, pattern);
    }

    public String getPrefix()
    {
      return prefix;
    }

    public SuffixMatch getSuffixMatch()
    {
      return suffixMatch;
    }

    @VisibleForTesting
    String describeCompilation()
    {
      StringBuilder description = new StringBuilder();

      description.append(likePattern).append(" => ");
      description.append(prefix).append(':');

      Iterator<LikePattern> iterator = pattern.iterator();
      while (iterator.hasNext()) {
        description.append(iterator.next());

        if (iterator.hasNext()) {
          description.append('|');
        }
      }

      return description.toString();
    }

    private static class LikePattern
    {
      private static class PatternPart
      {
        private final int leadingLength;
        private final String clause;

        public PatternPart(int leadingLength, String clause)
        {
          this.leadingLength = leadingLength;
          this.clause = clause;
        }

        @Override
        public String toString()
        {
          String escaped = StringUtils.replace(clause, "\\", "\\\\");
          escaped = StringUtils.replace(escaped, "%", "\\%");
          escaped = StringUtils.replace(escaped, "_", "\\_");
          return StringUtils.repeat("_", leadingLength) + escaped;
        }

        @Override
        public boolean equals(Object o)
        {
          if (this == o) {
            return true;
          }
          if (o == null || getClass() != o.getClass()) {
            return false;
          }
          PatternPart other = (PatternPart) o;
          return leadingLength == other.leadingLength &&
                 Objects.equals(clause, other.clause);
        }

        @Override
        public int hashCode()
        {
          return Objects.hash(clause, leadingLength);
        }
      }

      private final PatternPart[] parts;
      private final boolean anchored; // whether this is a "starts with" (foo) or "contains" (%foo) pattern
      private final int length; // number of characters that have to be matched, aka, how many literals and _ in the parts

      public LikePattern(boolean anchored, PatternPart... parts)
      {
        this.parts = parts;
        this.anchored = anchored;

        int length = 0;
        for (PatternPart part : parts) {
          length += part.leadingLength + part.clause.length();
        }
        this.length = length;
      }

      public int advance(String value, int offset, int endOffset)
      {
        endOffset = Math.min(Math.max(0, endOffset), value.length());

        if (offset > endOffset - length) {
          return -1;
        }

        if (anchored) {
          return advance(value, offset, endOffset, true);
        } else {
          while (offset < endOffset) { // TODO TODO TODO < or <= ?
            int matchOffset = advance(value, offset, endOffset, false);
            if (matchOffset != -1) {
              return matchOffset;
            }
            ++offset;
          }
        }

        return -1;
      }

      public boolean matchesSuffix(String value)
      {
        return advance(value, value.length() - length, value.length(), true) != -1;
      }

      private int advance(String value, int offset, int endOffset, boolean anchored)
      {
        int startIndex = 0;

        if (!anchored) {
          startIndex = 1;
          PatternPart first = parts[0];

          if (overflows(first, offset)) {
            return -1;
          }

          offset = value.indexOf(first.clause, offset + first.leadingLength);
          if (offset == -1 || offset > endOffset) { // TODO TODO TODO > or >= ?
            return -1;
          }
          offset += first.clause.length();
        }

        for (int i = startIndex; i < parts.length; i++) {
          PatternPart part = parts[i];

          // TODO TODO TODO > or >= ?
          if (overflows(part, offset) || offset + part.leadingLength + part.clause.length() > endOffset) {
            return -1;
          }

          if (!value.regionMatches(offset + part.leadingLength, part.clause, 0, part.clause.length())) {
            return -1;
          }

          offset += part.leadingLength + part.clause.length();
        }

        return offset;
      }

      // TODO TODO TODO
      private boolean overflows(PatternPart part, int offset)
      {
        // Even though overflow would be handled by regionMatches, CodeQL flags it as needing to be checked:
        // https://codeql.github.com/codeql-query-help/java/java-tainted-arithmetic/
        return part.leadingLength > Integer.MAX_VALUE - offset || offset > Integer.MAX_VALUE - part.leadingLength;
      }

      @Override
      public boolean equals(Object o)
      {
        if (this == o) {
          return true;
        }
        if (o == null || getClass() != o.getClass()) {
          return false;
        }
        LikePattern other = (LikePattern) o;
        return anchored == other.anchored && length == other.length && Arrays.equals(parts, other.parts);
      }

      @Override
      public int hashCode()
      {
        return Boolean.hashCode(anchored) ^ Arrays.hashCode(parts);
      }

      @Override
      public String toString()
      {
        StringBuilder result = new StringBuilder();
        if (!anchored) {
          result.append('%');
        }
        for (PatternPart part : parts) {
          result.append(part);
        }
        return result.toString();
      }
    }

    @VisibleForTesting
    static class PatternDruidPredicateFactory implements DruidPredicateFactory
    {
      private final ExtractionFn extractionFn;
      private final List<LikePattern> pattern;

      PatternDruidPredicateFactory(ExtractionFn extractionFn, List<LikePattern> pattern)
      {
        this.extractionFn = extractionFn;
        this.pattern = pattern;
      }

      @Override
      public DruidObjectPredicate<String> makeStringPredicate()
      {
        if (extractionFn != null) {
          return input -> matches(extractionFn.apply(input), pattern);
        } else {
          return input -> matches(input, pattern);
        }
      }

      @Override
      public DruidLongPredicate makeLongPredicate()
      {
        if (extractionFn != null) {
          return input -> matches(extractionFn.apply(input), pattern);
        } else {
          return input -> matches(String.valueOf(input), pattern);
        }
      }

      @Override
      public DruidFloatPredicate makeFloatPredicate()
      {
        if (extractionFn != null) {
          return input -> matches(extractionFn.apply(input), pattern);
        } else {
          return input -> matches(String.valueOf(input), pattern);
        }
      }

      @Override
      public DruidDoublePredicate makeDoublePredicate()
      {
        if (extractionFn != null) {
          return input -> matches(extractionFn.apply(input), pattern);
        } else {
          return input -> matches(String.valueOf(input), pattern);
        }
      }

      @Override
      public boolean equals(Object o)
      {
        if (this == o) {
          return true;
        }
        if (o == null || getClass() != o.getClass()) {
          return false;
        }
        PatternDruidPredicateFactory that = (PatternDruidPredicateFactory) o;
        return Objects.equals(extractionFn, that.extractionFn) &&
               Objects.equals(pattern, that.pattern);
      }

      @Override
      public int hashCode()
      {
        return Objects.hash(extractionFn, pattern);
      }
    }

    @Override
    public boolean equals(Object o)
    {
      if (this == o) {
        return true;
      }
      if (o == null || getClass() != o.getClass()) {
        return false;
      }
      LikeMatcher that = (LikeMatcher) o;
      return getSuffixMatch() == that.getSuffixMatch() &&
             Objects.equals(getPrefix(), that.getPrefix()) &&
             Objects.equals(pattern, that.pattern);
    }

    @Override
    public int hashCode()
    {
      return Objects.hash(getSuffixMatch(), getPrefix(), pattern);
    }

    @Override
    public String toString()
    {
      return likePattern;
    }
  }
}
