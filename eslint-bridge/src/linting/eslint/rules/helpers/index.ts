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

import { ParserServices } from '@typescript-eslint/experimental-utils';
import { RequiredParserServices } from 'eslint-plugin-sonarjs/lib/utils/parser-services';
import { debug } from 'helpers';

export * from './ancestor';
export * from './ast';
export * from './aws';
export * from './chai';
export * from './collection';
export * from './express';
export * from './file';
export * from './globals';
export * from './location';
export * from './lva';
export * from './mocha';
export * from './module';
export * from './quickfix';
export * from './reaching-definitions';
export * from './rule-detect-react';
export * from './type';

export function isRequiredParserServices(
  services: ParserServices | undefined,
): services is RequiredParserServices {
  const rps = !!services;
  debug('isRequiredParserServices ' + rps);
  return rps;
}

export { RequiredParserServices } from 'eslint-plugin-sonarjs/lib/utils/parser-services';
