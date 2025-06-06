/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import { InputGroup, Intent } from '@blueprintjs/core';
import { useState } from 'react';

function isoParseDate(dateString: string): Date | undefined {
  const dateParts = dateString.split(/[-T:. ]/g);

  // Extract the individual date and time components
  const year = parseInt(dateParts[0], 10);
  if (!(1000 < year && year < 4000)) return;

  const month = parseInt(dateParts[1], 10);
  if (month > 12) return;

  const day = parseInt(dateParts[2], 10);
  if (day > 31) return;

  const hour = parseInt(dateParts[3], 10);
  if (hour > 23) return;

  const minute = parseInt(dateParts[4], 10);
  if (minute > 59) return;

  const second = parseInt(dateParts[5], 10);
  if (second > 59) return;

  const millisecond = parseInt(dateParts[6], 10);
  if (millisecond >= 1000) return;

  const value = Date.UTC(year, month - 1, day, hour, minute, second, millisecond); // Month is zero-based
  if (isNaN(value)) return;

  return new Date(value);
}

function normalizeDateString(dateString: string): string {
  return dateString.replace(/[^\-0-9T:./Z ]/g, '');
}

function formatDate(date: Date): string {
  return date.toISOString().replace(/Z$/, '').replace('.000', '').replace(/T/g, ' ');
}

export interface UtcDateInputProps {
  date: Date;
  onChange(newDate: Date): void;
  onIssue(): void;
}

export function IsoDateInput(props: UtcDateInputProps) {
  const { date, onChange, onIssue } = props;
  const [invalidDateString, setInvalidDateString] = useState<string | undefined>();
  const [customDateString, setCustomDateString] = useState<string | undefined>();
  const [focused, setFocused] = useState<boolean>(false);

  return (
    <InputGroup
      className="iso-date-input"
      placeholder="yyyy-MM-dd HH:mm:ss"
      intent={!focused && invalidDateString ? Intent.DANGER : undefined}
      value={
        invalidDateString ??
        (customDateString && isoParseDate(customDateString)?.valueOf() === date.valueOf()
          ? customDateString
          : undefined) ??
        formatDate(date)
      }
      onChange={e => {
        const normalizedDateString = normalizeDateString(e.target.value);
        const parsedDate = isoParseDate(normalizedDateString);
        if (parsedDate) {
          onChange(parsedDate);
          setInvalidDateString(undefined);
          setCustomDateString(normalizedDateString);
        } else {
          onIssue();
          setInvalidDateString(normalizedDateString);
          setCustomDateString(undefined);
        }
      }}
      onFocus={() => setFocused(true)}
      onBlur={() => setFocused(false)}
    />
  );
}
