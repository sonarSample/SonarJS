/*
 * SonarQube JavaScript Plugin
 * Copyright (C) 2011-2022 SonarSource SA
 * mailto:info AT sonarsource DOT com
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
import { rules } from '@typescript-eslint/eslint-plugin';
import { TypeScriptRuleTester } from '../../../tools';

const ruleTesterTs = new TypeScriptRuleTester();

ruleTesterTs.run('bla', rules['no-unnecessary-type-assertion'], {
  valid: [
    {
      code: `
export function isPositive(x: number) {
  return x > 0;
}
export type PositiveNumberRecord<T extends string | number | symbol> = Record<T, number> & {
  __type: 'positiveNumberRecord';
};
export const isPositiveNumberRecord = <T extends string | number | symbol>(
  value: Record<T, number> | null | undefined,
): value is PositiveNumberRecord<T> => {
  if (!value) {
    return false;
  }

  const values = Object.values(value) as number[];

  return values.every(isPositive);
};
      `,
    },
  ],
  invalid: []
});
