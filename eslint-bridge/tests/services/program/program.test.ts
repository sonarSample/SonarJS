/*
 * SonarQube JavaScript Plugin
 * Copyright (C) 2011-2023 SonarSource SA
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
import path from 'path';
import {
  createProgram,
  createProgramOptions,
  deleteProgram,
  getProgramById,
  isRootNodeModules,
} from 'services/program';
import { toUnixPath } from 'helpers';
import ts, { ModuleKind, ScriptTarget } from 'typescript';
import { writeTSConfigFile } from 'services/program';
import fs from 'fs/promises';

const defaultParseConfigHost: ts.ParseConfigHost = {
  useCaseSensitiveFileNames: true,
  readDirectory: ts.sys.readDirectory,
  fileExists: ts.sys.fileExists,
  readFile: ts.sys.readFile,
};

describe('program', () => {
  it('should create a program', async () => {
    const fixtures = path.join(__dirname, 'fixtures');
    const reference = path.join(fixtures, 'reference');
    const tsConfig = path.join(fixtures, 'tsconfig.json');

    const { programId, files, projectReferences } = await createProgram(tsConfig);

    expect(programId).toBeDefined();
    expect(files).toEqual(
      expect.arrayContaining([
        toUnixPath(path.join(fixtures, 'file.ts')),
        toUnixPath(path.join(reference, 'file.ts')),
      ]),
    );
    expect(projectReferences).toEqual([path.join(reference, 'tsconfig.json')]);
  });

  it('should skip missing reference of a program', async () => {
    const fixtures = path.join(__dirname, 'fixtures');
    const tsConfig = path.join(fixtures, `tsconfig_missing_reference.json`);

    const { programId, files, projectReferences, missingTsConfig } = await createProgram(tsConfig);

    expect(programId).toBeDefined();
    expect(files).toEqual(expect.arrayContaining([toUnixPath(path.join(fixtures, 'file.ts'))]));
    expect(projectReferences).toEqual([]);
    expect(missingTsConfig).toBe(false);
  });

  it('should fail creating a program with a syntactically incorrect tsconfig', async () => {
    const tsConfig = path.join(__dirname, 'fixtures', 'tsconfig.syntax.json');
    const error = await createProgram(tsConfig).catch(err => err);
    expect(error).toBeInstanceOf(Error);
  });

  it('should fail creating a program with a semantically incorrect tsconfig', async () => {
    const tsConfig = path.join(__dirname, 'fixtures', 'tsconfig.semantic.json');
    const error = await createProgram(tsConfig).catch(err => err);
    expect(error.message).toMatch(/^Unknown compiler option 'targetSomething'./);
  });

  it('should still create a program when extended tsconfig does not exist', async () => {
    const fixtures = path.join(__dirname, 'fixtures');
    const tsConfig = path.join(fixtures, 'tsconfig_missing.json');

    const { programId, files, projectReferences, missingTsConfig } = await createProgram(tsConfig);

    expect(programId).toBeDefined();
    expect(files).toEqual(expect.arrayContaining([toUnixPath(path.join(fixtures, 'file.ts'))]));
    expect(projectReferences).toEqual([]);
    expect(missingTsConfig).toBe(true);
  });

  it('On missing external tsconfig, Typescript should generate default compilerOptions', () => {
    const fixtures = path.join(__dirname, 'fixtures');
    const tsConfigMissing = path.join(fixtures, 'tsconfig_missing.json');

    const { options, missingTsConfig } = createProgramOptions(tsConfigMissing);

    expect(missingTsConfig).toBe(true);
    expect(options).toEqual({
      configFilePath: toUnixPath(path.join(fixtures, 'tsconfig_missing.json')),
      noEmit: true,
      allowNonTsExtensions: true,
    });
  });

  it('External tsconfig should provide expected compilerOptions', () => {
    const tsConfig = path.join(__dirname, 'fixtures', 'tsconfig_found.json');

    const { options, missingTsConfig } = createProgramOptions(tsConfig);

    expect(missingTsConfig).toBe(false);
    expect(options).toBeDefined();
    expect(options.target).toBe(ScriptTarget['ES2020']);
    expect(options.module).toBe(ModuleKind['CommonJS']);
  });

  /**
   * Empty tsconfig.json fallback relies on Typescript resolution logic. This unit test
   * asserts typescript resolution logic. If it changes, we will need to adapt our logic inside
   * program.ts (createProgramOptions in program.ts)
   */
  it('typescript tsconfig resolution should check all paths until root node_modules', async () => {
    const configHost = {
      useCaseSensitiveFileNames: true,
      readDirectory: ts.sys.readDirectory,
      fileExists: jest.fn((_file: string) => false),
      readFile: ts.sys.readFile,
    };

    const tsConfigMissing = path.join(__dirname, 'fixtures', 'tsconfig_missing.json');
    const searchedFiles = [];
    let nodeModulesFolder = path.join(__dirname, 'fixtures');
    let searchFolder;
    do {
      searchFolder = path.join(nodeModulesFolder, 'node_modules', '@tsconfig', 'node_missing');
      searchedFiles.push(path.join(searchFolder, 'tsconfig.json', 'package.json'));
      searchedFiles.push(path.join(searchFolder, 'package.json'));
      searchedFiles.push(path.join(searchFolder, 'tsconfig.json'));
      searchedFiles.push(path.join(searchFolder, 'tsconfig.json', 'tsconfig.json'));
      nodeModulesFolder = path.dirname(nodeModulesFolder);
    } while (!isRootNodeModules(searchFolder));

    const config = ts.readConfigFile(tsConfigMissing, configHost.readFile);
    const parsedConfigFile = ts.parseJsonConfigFileContent(
      config.config,
      configHost,
      path.resolve(path.dirname(tsConfigMissing)),
      {
        noEmit: true,
      },
      tsConfigMissing,
    );

    expect(parsedConfigFile.errors).not.toHaveLength(0);
    expect(configHost.fileExists).toHaveBeenCalledTimes(searchedFiles.length);
    searchedFiles.forEach((file, index) => {
      expect(configHost.fileExists).toHaveBeenNthCalledWith(index + 1, toUnixPath(file));
    });
  });

  it('should find an existing program', async () => {
    const fixtures = path.join(__dirname, 'fixtures');
    const tsConfig = path.join(fixtures, 'tsconfig.json');
    const { programId, files } = await createProgram(tsConfig);

    const program = getProgramById(programId);

    expect(program.getCompilerOptions().configFilePath).toEqual(toUnixPath(tsConfig));
    expect(program.getRootFileNames()).toEqual(
      files.map(toUnixPath).filter(file => file.startsWith(toUnixPath(fixtures))),
    );
  });

  it('should fail finding a non-existing program', () => {
    const programId = '$#&/()=?!£@~+°';
    expect(() => getProgramById(programId)).toThrow(`Failed to find program ${programId}`);
  });

  it('should delete a program', async () => {
    const fixtures = path.join(__dirname, 'fixtures');
    const tsConfig = path.join(fixtures, 'tsconfig.json');
    const { programId } = await createProgram(tsConfig);

    deleteProgram(programId);
    expect(() => getProgramById(programId)).toThrow(`Failed to find program ${programId}`);
  });

  it('should return files', () => {
    const readFile = _path => `{ "files": ["/foo/file.ts"] }`;
    const result = createProgramOptions('tsconfig.json', { ...defaultParseConfigHost, readFile });
    expect(result).toMatchObject({
      rootNames: ['/foo/file.ts'],
      projectReferences: undefined,
    });
  });

  it('should report errors', () => {
    const readFile = _path => `{ "files": [] }`;
    expect(() =>
      createProgramOptions('tsconfig.json', { ...defaultParseConfigHost, readFile }),
    ).toThrow(`The 'files' list in config file 'tsconfig.json' is empty.`);
  });

  it('should return projectReferences', () => {
    const readFile = _path => `{ "files": [], "references": [{ "path": "foo" }] }`;
    const result = createProgramOptions('tsconfig.json', { ...defaultParseConfigHost, readFile });
    const cwd = process.cwd().split(path.sep).join(path.posix.sep);
    expect(result).toMatchObject({
      rootNames: [],
      projectReferences: [expect.objectContaining({ path: `${cwd}/foo` })],
    });
  });

  it('jsonParse does not resolve imports, createProgram does', async () => {
    const fixtures = toUnixPath(path.join(__dirname, 'fixtures'));
    const tsConfig = toUnixPath(path.join(fixtures, 'paths', 'tsconfig.json'));
    const mainFile = toUnixPath(path.join(fixtures, 'paths', 'file.ts'));
    const dependencyPath = toUnixPath(path.join(fixtures, 'paths', 'subfolder', 'index.ts'));
    let { rootNames: files } = createProgramOptions(tsConfig);
    expect(files).toContain(mainFile);
    expect(files).not.toContain(dependencyPath);

    files = (await createProgram(tsConfig)).files;
    expect(files).toContain(mainFile);
    expect(files).toContain(dependencyPath);
  });

  it('should return Vue files', () => {
    const fixtures = path.join(__dirname, 'fixtures', 'vue');
    const tsConfig = path.join(fixtures, 'tsconfig.json');
    const result = createProgramOptions(tsConfig);
    expect(result).toEqual(
      expect.objectContaining({
        rootNames: expect.arrayContaining([toUnixPath(path.join(fixtures, 'file.vue'))]),
      }),
    );
  });

  it('should write tsconfig file', async () => {
    const { filename } = await writeTSConfigFile({
      compilerOptions: { allowJs: true, noImplicitAny: true },
      include: ['/path/to/project/**/*'],
    });
    const content = await fs.readFile(filename, { encoding: 'utf-8' });
    expect(content).toBe(
      '{"compilerOptions":{"allowJs":true,"noImplicitAny":true},"include":["/path/to/project/**/*"]}',
    );
  });
});
