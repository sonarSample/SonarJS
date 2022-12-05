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
package org.sonar.plugins.javascript.eslint;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import org.sonar.api.batch.fs.FilePredicates;
import org.sonar.api.batch.fs.InputFile;
import org.sonar.api.batch.sensor.SensorDescriptor;
import org.sonar.api.utils.log.Logger;
import org.sonar.api.utils.log.Loggers;
import org.sonar.plugins.javascript.CancellationException;
import org.sonar.plugins.javascript.eslint.EslintBridgeServer.JsAnalysisRequest;
import org.sonar.plugins.javascript.eslint.cache.CacheStrategies;
import org.sonar.plugins.javascript.eslint.cache.CacheStrategy;
import org.sonar.plugins.javascript.utils.ProgressReport;

public class HtmlSensor extends AbstractEslintSensor {

  public static final String LANGUAGE = "web";

  private static final Logger LOG = Loggers.get(HtmlSensor.class);
  private final JavaScriptChecks checks;
  private final AnalysisProcessor analysisProcessor;
  private AnalysisMode analysisMode;

  public HtmlSensor(
      JavaScriptChecks checks,
      EslintBridgeServer eslintBridgeServer,
      AnalysisWarningsWrapper analysisWarnings,
      Monitoring monitoring,
      AnalysisProcessor processAnalysis) {
    // The monitoring sensor remains inactive during YAML files analysis, as the
    // bridge doesn't provide nor compute metrics for such files.
    super(eslintBridgeServer, analysisWarnings, monitoring);
    this.checks = checks;
    this.analysisProcessor = processAnalysis;
  }

  @Override
  public void describe(SensorDescriptor descriptor) {
    descriptor
      .name("JavaScript inside HTML analysis")
      .onlyOnLanguage(LANGUAGE);
  }

  @Override
  protected void analyzeFiles(List<InputFile> inputFiles) throws IOException {
    analysisMode = AnalysisMode.getMode(context, checks.eslintRules());
    var progressReport = new ProgressReport("Analysis progress", TimeUnit.SECONDS.toMillis(10));
    var success = false;
    try {
      progressReport.start(inputFiles.size(), inputFiles.iterator().next().absolutePath());
      eslintBridgeServer.initLinter(checks.eslintRules(), environments, globals, analysisMode);
      for (var inputFile : inputFiles) {
        if (context.isCancelled()) {
          throw new CancellationException("Analysis interrupted because the SensorContext is in cancelled state");
        }
        if (eslintBridgeServer.isAlive()) {
          progressReport.nextFile(inputFile.absolutePath());
          var cacheStrategy = CacheStrategies.getStrategyFor(context, inputFile);
          if (cacheStrategy.isAnalysisRequired()) {
            analyze(inputFile, cacheStrategy);
          }
        } else {
          throw new IllegalStateException("eslint-bridge server is not answering");
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

  @Override
  protected List<InputFile> getInputFiles() {
    var fileSystem = context.fileSystem();
    FilePredicates p = fileSystem.predicates();
    var filePredicate = p.hasLanguage(HtmlSensor.LANGUAGE);
    var inputFiles = context.fileSystem().inputFiles(filePredicate);
    return StreamSupport.stream(inputFiles.spliterator(), false).collect(Collectors.toList());
  }

  private void analyze(InputFile file, CacheStrategy cacheStrategy) throws IOException {
    try {
      LOG.debug("Analyzing file: {}", file.uri());
      var fileContent = contextUtils.shouldSendFileContent(file) ? file.contents() : null;
      var jsAnalysisRequest = new JsAnalysisRequest(
        file.absolutePath(),
        file.type().toString(),
        fileContent,
        contextUtils.ignoreHeaderComments(),
        null,
        null,
        analysisMode.getLinterIdFor(file));
      var response = eslintBridgeServer.analyzeHtml(jsAnalysisRequest);
      analysisProcessor.processResponse(context, checks, file, response);
      cacheStrategy.writeGeneratedFilesToCache(response.ucfgPaths);
    } catch (IOException e) {
      LOG.error("Failed to get response while analyzing " + file.uri(), e);
      throw e;
    }
  }
}