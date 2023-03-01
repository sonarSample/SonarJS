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
package org.sonar.plugins.javascript.eslint;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import javax.annotation.Nullable;
import org.sonar.api.batch.fs.InputFile;
import org.sonar.api.scanner.ScannerSide;
import org.sonar.api.utils.log.Logger;
import org.sonar.api.utils.log.Loggers;
import org.sonar.plugins.javascript.CancellationException;
import org.sonar.plugins.javascript.JavaScriptLanguage;
import org.sonar.plugins.javascript.TypeScriptLanguage;
import org.sonar.plugins.javascript.eslint.cache.CacheAnalysis;
import org.sonar.plugins.javascript.eslint.cache.CacheStrategies;
import org.sonar.plugins.javascript.utils.ProgressReport;
import org.sonarsource.api.sonarlint.SonarLintSide;

import static java.util.Collections.singletonList;

@ScannerSide
@SonarLintSide
public class AnalysisWithWatchProgram extends AbstractAnalysis {

  private static final Logger LOG = Loggers.get(AnalysisWithWatchProgram.class);

  public AnalysisWithWatchProgram(EslintBridgeServer eslintBridgeServer, Monitoring monitoring, AnalysisProcessor analysisProcessor) {
    super(eslintBridgeServer, monitoring, analysisProcessor);
  }

  @Override
  public void analyzeFiles(List<InputFile> inputFiles) throws IOException {
    List<String> tsConfigs = new TsConfigProvider(tempFolder).tsconfigs(context);
    if (tsConfigs.isEmpty()) {
      // This can happen where we are not able to create temporary file for generated tsconfig.json
      LOG.warn("No tsconfig.json file found, analysis will be skipped.");
      return;
    }

    boolean success = false;
    progressReport = new ProgressReport(PROGRESS_REPORT_TITLE, PROGRESS_REPORT_PERIOD);
    Map<TsConfigFile, List<InputFile>> filesByTsConfig = TsConfigFile.inputFilesByTsConfig(loadTsConfigs(tsConfigs), inputFiles);
    try {
      progressReport.start(inputFiles.size(), inputFiles.iterator().next().absolutePath());
      if (tsConfigs.isEmpty()) {
        LOG.info("Analyzing {} files without tsconfig", inputFiles.size());
        analyzeTsConfig(null, inputFiles);
      } else {
        for (Map.Entry<TsConfigFile, List<InputFile>> entry : filesByTsConfig.entrySet()) {
          TsConfigFile tsConfigFile = entry.getKey();
          List<InputFile> files = entry.getValue();
          if (TsConfigFile.UNMATCHED_CONFIG.equals(tsConfigFile)) {
            LOG.info("Skipping {} files with no tsconfig.json", files.size());
            LOG.debug("Skipped files: " + files.stream().map(InputFile::toString).collect(Collectors.joining("\n")));
            continue;
          }
          LOG.info("Analyzing {} files using tsconfig: {}", files.size(), tsConfigFile);
          analyzeTsConfig(tsConfigFile, files);
          // Clear Watch Program Cache. Useful only for SonarQube with Vue files. To be removed when only in SonarLint. Test Out of memory
          eslintBridgeServer.newTsConfig();
        }
      }
      success = true;
    } finally {
      if (success) {
        progressReport.stop();
      } else {
        progressReport.cancel();
      }
    }
  }

  private List<TsConfigFile> loadTsConfigs(List<String> tsConfigPaths) {
    List<TsConfigFile> tsConfigFiles = new ArrayList<>();
    Deque<String> workList = new ArrayDeque<>(tsConfigPaths);
    Set<String> processed = new HashSet<>();
    while (!workList.isEmpty()) {
      String path = workList.pop();
      if (processed.add(path)) {
        TsConfigFile tsConfigFile = eslintBridgeServer.loadTsConfig(path);
        tsConfigFiles.add(tsConfigFile);
        if (!tsConfigFile.projectReferences.isEmpty()) {
          LOG.debug("Adding referenced project's tsconfigs {}", tsConfigFile.projectReferences);
        }
        workList.addAll(tsConfigFile.projectReferences);
      }
    }
    return tsConfigFiles;
  }

  private void analyzeTsConfig(@Nullable TsConfigFile tsConfigFile, List<InputFile> files) throws IOException {
    for (InputFile inputFile : files) {
      if (context.isCancelled()) {
        throw new CancellationException("Analysis interrupted because the SensorContext is in cancelled state");
      }
      if (eslintBridgeServer.isAlive()) {
        monitoring.startFile(inputFile);
        analyze(inputFile, tsConfigFile);
        progressReport.nextFile(inputFile.absolutePath());
      } else {
        throw new IllegalStateException("eslint-bridge server is not answering");
      }
    }
  }

  private void analyze(InputFile file, @Nullable TsConfigFile tsConfigFile) throws IOException {
    var cacheStrategy = CacheStrategies.getStrategyFor(context, file);
    if (cacheStrategy.isAnalysisRequired()) {
      try {
        LOG.debug("Analyzing file: " + file.uri());
        var fileContent = contextUtils.shouldSendFileContent(file) ? file.contents() : null;
        var tsConfigs = tsConfigFile == null ? Collections.<String>emptyList() : singletonList(tsConfigFile.filename);
        var request = new EslintBridgeServer.JsAnalysisRequest(file.absolutePath(), file.type().toString(), fileContent,
          contextUtils.ignoreHeaderComments(), tsConfigs, null, analysisMode.getLinterIdFor(file));
        EslintBridgeServer.AnalysisResponse response;
        if (TypeScriptLanguage.KEY.equals(file.language())) {
          response = eslintBridgeServer.analyzeTypeScript(request);
        } else if (JavaScriptLanguage.KEY.equals(file.language())) {
          response = eslintBridgeServer.analyzeJavaScript(request);
        } else {
          throw new UnsupportedOperationException();
        }
        analysisProcessor.processResponse(context, checks, file, response);
        cacheStrategy.writeAnalysisToCache(CacheAnalysis.fromResponse(response.ucfgPaths, response.cpdTokens), file);
      } catch (IOException e) {
        LOG.error("Failed to get response while analyzing " + file.uri(), e);
        throw e;
      }
    } else {
      LOG.debug("Processing cache analysis of file: {}", file.uri());
      var cacheAnalysis = cacheStrategy.readAnalysisFromCache();
      analysisProcessor.processCacheAnalysis(context, file, cacheAnalysis);
    }
  }

}
