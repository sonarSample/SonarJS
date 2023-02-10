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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.annotation.Nullable;

import org.sonar.api.batch.fs.InputFile.Type;
import org.sonar.api.batch.rule.CheckFactory;
import org.sonar.api.batch.rule.Checks;
import org.sonar.api.rule.RuleKey;
import org.sonar.api.utils.log.Logger;
import org.sonar.api.utils.log.Loggers;
import org.sonar.javascript.checks.ParsingErrorCheck;
import org.sonar.plugins.javascript.api.CustomRuleRepository;
import org.sonar.plugins.javascript.api.EslintBasedCheck;
import org.sonar.plugins.javascript.api.JavaScriptCheck;

public class AbstractChecks {
  private static final Logger LOG = Loggers.get(AbstractChecks.class);
  private final CheckFactory checkFactory;
  private final CustomRuleRepository[] customRuleRepositories;
  private final Set<Checks<JavaScriptCheck>> checksByRepository = new HashSet<>();
  private RuleKey parseErrorRuleKey;
  private Map<String, String> eslintToSonarKey;
  private List<EslintRule> checks;

  public AbstractChecks(CheckFactory checkFactory, @Nullable CustomRuleRepository[] customRuleRepositories) {
    this.checkFactory = checkFactory;
    this.customRuleRepositories = customRuleRepositories;
    this.buildChecks();
  }

  private void buildChecks() {
    checks = new ArrayList<EslintRule>();
    checks.add(new EslintRule("no-undef", new ArrayList<Object>(), List.of(Type.MAIN, Type.TEST)));
    checks.add(new EslintRule("no-useless-backreference", new ArrayList<Object>(), List.of(Type.MAIN, Type.TEST)));
    checks.add(new EslintRule("no-loss-of-precision", new ArrayList<Object>(), List.of(Type.MAIN, Type.TEST)));
    checks.add(new EslintRule("no-var", new ArrayList<Object>(), List.of(Type.MAIN, Type.TEST)));
    eslintToSonarKey = new HashMap<String, String>();
    for (int i=0; i<checks.size(); i++) {
      eslintToSonarKey.put(checks.get(i).getKey(), sway[i]);
    }
  }

  protected void addChecks(CustomRuleRepository.Language language, String repositoryKey, Iterable<Class<? extends JavaScriptCheck>> checkClass) {
    doAddChecks(repositoryKey, checkClass);
    addCustomChecks(language);
  }

  private void doAddChecks(String repositoryKey, Iterable<Class<? extends JavaScriptCheck>> checkClass) {
    checksByRepository.add(checkFactory
      .<JavaScriptCheck>create(repositoryKey)
      .addAnnotatedChecks(checkClass));
  }

  private void addCustomChecks(CustomRuleRepository.Language language) {

    if (customRuleRepositories != null) {
      for (CustomRuleRepository repo : customRuleRepositories) {
        if (repo.languages().contains(language)) {
          LOG.debug("Adding rules for repository '{}', language: {}, {} from {}", repo.repositoryKey(), language,
            repo.checkClasses(),
            repo.getClass().getCanonicalName());
          doAddChecks(repo.repositoryKey(), repo.checkClasses());
        }
      }
    }

  }

  private Stream<JavaScriptCheck> all() {
    return checksByRepository.stream()
      .flatMap(checks -> checks.all().stream());
  }

  Stream<EslintBasedCheck> eslintBasedChecks() {
    return all()
      .filter(EslintBasedCheck.class::isInstance)
      .map(EslintBasedCheck.class::cast);
  }

  @Nullable
  public RuleKey ruleKeyFor(JavaScriptCheck check) {
    RuleKey ruleKey;

    for (Checks<JavaScriptCheck> checks : checksByRepository) {
      ruleKey = checks.ruleKey(check);

      if (ruleKey != null) {
        return ruleKey;
      }
    }
    return null;
  }

  @Nullable
  public RuleKey ruleKeyByEslintKey(String eslintKey) {

    // translate eslint key -> its mapping squatted Skey
    // we need to find
    var sonarKey = this.eslintToSonarKey.get(eslintKey);

    for (Checks<JavaScriptCheck> checks : checksByRepository) {
      for (JavaScriptCheck check : checks.all()) {
        if (check instanceof EslintBasedCheck) {
          var ruleKey = checks.ruleKey(check);
          var trueSonarKey = ruleKey.rule();
          if (trueSonarKey.equals(sonarKey)) return ruleKey;
        }
      }
    }
    return null;
  }

  /**
   * parsingErrorRuleKey equals null if ParsingErrorCheck is not activated
   *
   * @return rule key for parse error
   */
  @Nullable
  RuleKey parsingErrorRuleKey() {
    return parseErrorRuleKey;
  }

  protected void initParsingErrorRuleKey() {
    this.parseErrorRuleKey = all()
      .filter(ParsingErrorCheck.class::isInstance)
      .findFirst()
      .map(this::ruleKeyFor).orElse(null);
  }

  List<EslintRule> eslintRules() {
    List<EslintRule> checks = new ArrayList<EslintRule>();
    checks.add(new EslintRule("no-unexpected-multiline", new ArrayList<Object>(), List.of(Type.MAIN, Type.TEST)));
    checks.add(new EslintRule("no-useless-backreference", new ArrayList<Object>(), List.of(Type.MAIN, Type.TEST)));
    return eslintBasedChecks()
      .map(check -> new EslintRule(check.eslintKey(), check.configurations(), check.targets()))
      .collect(Collectors.toList());
  }

  final static String[] sway = {
    "S101",
    "S107",
    "S108",
    "S125",
    "S128",
    "S878",
    "S888",
    "S905",
    "S930",
    "S1119",
    "S1121",
    "S1125",
    "S1126",
    "S1128",
    "S1134",
    "S1135",
    "S1143",
    "S1186",
    "S1219",
    "S1226",
    "S1264",
    "S1301",
    "S1313",
    "S1314",
    "S1321",
    "S1439",
    "S1472",
    "S1479",
    "S1481",
    "S1515",
    "S1516",
    "S1523",
    "S1527",
    "S1529",
    "S1533",
    "S1534",
    "S1536",
    "S1656",
    "S1751",
    "S1763",
    "S1764",
    "S1788",
    "S1848",
    "S1854",
    "S1862",
    "S1871",
    "S1874",
    "S1940",
    "S1994",
    "S2068",
    "S2077",
    "S2092",
    "S2123",
    "S2137",
    "S2189",
    "S2201",
    "S2234",
    "S2245",
    "S2251",
    "S2259",
    "S2310",
    "S2392",
    "S2432",
    "S2589",
    "S2598",
    "S2612",
    "S2681",
    "S2685",
    "S2688",
    "S2692",
    "S2699",
    "S2703",
    "S2737",
    "S2755",
    "S2757",
    "S2814",
    "S2819",
    "S2870",
    "S2871",
    "S2970",
    "S2990",
    "S2999",
    "S3001",
    "S3330",
    "S3358",
    "S3403",
    "S3415",
    "S3500",
    "S3504",
    "S3516",
    "S3531",
    "S3579",
    "S3616",
    "S3626",
    "S3686",
    "S3696",
    "S3699",
    "S3735",
    "S3776",
    "S3782",
    "S3785",
    "S3796",
    "S3799",
    "S3800",
    "S3812",
    "S3834",
    "S3854",
    "S3863",
    "S3923",
    "S3972",
    "S3981",
    "S3984",
    "S4030",
    "S4036",
    "S4043",
    "S4123",
    "S4124",
    "S4125",
    "S4138",
    "S4140",
    "S4143",
    "S4144",
    "S4156",
    "S4158",
    "S4165",
    "S4275",
    "S4322",
    "S4323",
    "S4325",
    "S4335",
    "S4423",
    "S4426",
    "S4502",
    "S4507",
    "S4524",
    "S4619",
    "S4621",
    "S4623",
    "S4624",
    "S4634",
    "S4721",
    "S4782",
    "S4790",
    "S4822",
    "S4830",
    "S5042",
    "S5122",
    "S5148",
    "S5247",
    "S5332",
    "S5443",
    "S5527",
    "S5542",
    "S5547",
    "S5604",
    "S5659",
    "S5689",
    "S5691",
    "S5693",
    "S5725",
    "S5728",
    "S5730",
    "S5732",
    "S5734",
    "S5736",
    "S5739",
    "S5742",
    "S5743",
    "S5757",
    "S5759",
    "S5842",
    "S5843",
    "S5850",
    "S5852",
    "S5856",
    "S5860",
    "S5863",
    "S5868",
    "S5869",
    "S5876",
    "S5958",
    "S6019",
    "S6035",
    "S6079",
    "S6080",
    "S6092",
    "S6245",
    "S6249",
    "S6252",
    "S6265",
    "S6268",
    "S6270",
    "S6275",
    "S6281",
    "S6299",
    "S6302",
    "S6303",
    "S6308",
    "S6317",
    "S6319",
    "S6321",
    "S6323",
    "S6324",
    "S6325",
    "S6326",
    "S6327",
    "S6328",
    "S6329",
    "S6330",
    "S6331",
    "S6332",
    "S6333",
    "S6351",
    "S6353",
    "S6397",
    "S6426",
    "S6435",
    "S6438",
    "S6439",
    "S6440",
    "S6441",
    "S6442",
    "S6443",
    "S6477",
    "S6478",
    "S6479",
    "S6481",
    "S6486"
  };
}



