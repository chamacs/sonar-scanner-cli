/*
 * SonarScanner CLI
 * Copyright (C) 2011-2025 SonarSource SA
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
package org.sonarsource.scanner.cli;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sonarsource.scanner.lib.EnvironmentConfig;

import static java.nio.charset.StandardCharsets.UTF_8;

class Conf {
  private static final Logger LOG = LoggerFactory.getLogger(Conf.class);

  private static final String SCANNER_HOME = "scanner.home";
  private static final String SCANNER_SETTINGS = "scanner.settings";
  private static final String PROJECT_HOME = "project.home";
  private static final String PROJECT_SETTINGS = "project.settings";
  private static final String PROPERTY_MODULES = "sonar.modules";
  private static final String PROPERTY_PROJECT_BASEDIR = "sonar.projectBaseDir";
  private static final String PROPERTY_PROJECT_CONFIG_FILE = "sonar.projectConfigFile";
  private static final String SONAR_PROJECT_PROPERTIES_FILENAME = "sonar-project.properties";
  private static final String BOOTSTRAP_START_TIME = "sonar.scanner.bootstrapStartTime";

  private final Cli cli;
  private final Map<String, String> env;
  private final long startTimeMs;

  Conf(Cli cli, Map<String, String> env) {
    this.cli = cli;
    this.env = env;
    this.startTimeMs = System.currentTimeMillis();
  }

  Properties properties() {
    Properties result = new Properties();
    result.putAll(loadGlobalProperties());
    result.putAll(loadProjectProperties());
    result.putAll(System.getProperties());
    result.putAll(loadEnvironmentProperties());
    result.putAll(cli.properties());
    result = resolve(result);

    // root project base directory must be present and be absolute
    result.setProperty(PROPERTY_PROJECT_BASEDIR, getRootProjectBaseDir(result).toString());
    result.remove(PROJECT_HOME);

    result.setProperty(BOOTSTRAP_START_TIME, String.valueOf(startTimeMs));
    return result;
  }

  private Properties resolve(Properties props) {
    PropertyResolver resolver = new PropertyResolver(props, env);
    return resolver.resolve();
  }

  private static Map<String, String> loadEnvironmentProperties() {
    return EnvironmentConfig.load();
  }

  private Properties loadGlobalProperties() {
    Properties knownPropsAtThatPoint = new Properties();

    knownPropsAtThatPoint.putAll(System.getProperties());
    knownPropsAtThatPoint.putAll(loadEnvironmentProperties());
    knownPropsAtThatPoint.putAll(cli.properties());

    Path settingsFile = locatePropertiesFile(knownPropsAtThatPoint);
    if (settingsFile != null && Files.isRegularFile(settingsFile)) {
      LOG.info("Scanner configuration file: {}", settingsFile);
      return toProperties(settingsFile);
    }
    LOG.info("Scanner configuration file: NONE");
    return new Properties();
  }

  private Properties loadProjectProperties() {
    Properties rootProps = new Properties();
    Properties knownPropsAtThatPoint = new Properties();

    knownPropsAtThatPoint.putAll(System.getProperties());
    knownPropsAtThatPoint.putAll(loadEnvironmentProperties());
    knownPropsAtThatPoint.putAll(cli.properties());

    Path defaultRootSettingsFile = getRootProjectBaseDir(knownPropsAtThatPoint).resolve(SONAR_PROJECT_PROPERTIES_FILENAME);
    Path rootSettingsFile = locatePropertiesFile(defaultRootSettingsFile, knownPropsAtThatPoint, PROJECT_SETTINGS);
    if (rootSettingsFile != null && Files.isRegularFile(rootSettingsFile)) {
      LOG.info("Project root configuration file: {}", rootSettingsFile);
      rootProps.putAll(toProperties(rootSettingsFile));
    } else {
      LOG.info("Project root configuration file: NONE");
    }

    Properties projectProps = new Properties();

    // include already root base directory and eventually props loaded from
    // root config file
    projectProps.putAll(rootProps);

    rootProps.putAll(knownPropsAtThatPoint);
    rootProps.setProperty(PROPERTY_PROJECT_BASEDIR, getRootProjectBaseDir(rootProps).toString());

    // projectProps will be overridden by any properties found in child
    // project settings
    loadModulesProperties(rootProps, projectProps, "");
    return projectProps;
  }

  private static Path getRootProjectBaseDir(Properties cliProps) {
    Path absoluteProjectHome;
    if (cliProps.containsKey(PROJECT_HOME)) {
      absoluteProjectHome = Paths.get(cliProps.getProperty(PROJECT_HOME)).toAbsolutePath();
    } else {
      // this should always be avoided, as it will resolve symbolic links
      absoluteProjectHome = Paths.get("").toAbsolutePath();
    }

    if (!cliProps.containsKey(PROPERTY_PROJECT_BASEDIR)) {
      return absoluteProjectHome;
    }

    return getAbsolutePath(cliProps.getProperty(PROPERTY_PROJECT_BASEDIR), absoluteProjectHome);
  }

  private void loadModulesProperties(Properties parentProps, Properties projectProps, String prefix) {
    Path parentBaseDir = Paths.get(parentProps.getProperty(PROPERTY_PROJECT_BASEDIR));
    if (parentProps.containsKey(PROPERTY_MODULES)) {
      for (String module : getListFromProperty(parentProps, PROPERTY_MODULES)) {
        Properties moduleProps = extractModuleProperties(module, parentProps);
        // higher priority to child configuration files
        loadModuleConfigFile(parentBaseDir, moduleProps, module);

        // the child project may have children as well
        loadModulesProperties(moduleProps, projectProps, prefix + module + ".");
        // and finally add this child properties to global props
        merge(projectProps, prefix, module, moduleProps);
      }
    }

  }

  private static void merge(Properties projectProps, String prefix, String module, Properties moduleProps) {
    for (Map.Entry<Object, Object> entry : moduleProps.entrySet()) {
      projectProps.put(prefix + module + "." + entry.getKey(), entry.getValue());
    }
  }

  private void loadModuleConfigFile(Path parentAbsBaseDir, Properties moduleProps, String moduleId) {
    final Path absoluteBaseDir;

    if (moduleProps.containsKey(PROPERTY_PROJECT_BASEDIR)) {
      absoluteBaseDir = getAbsolutePath(moduleProps.getProperty(PROPERTY_PROJECT_BASEDIR), parentAbsBaseDir);
      setModuleBaseDir(absoluteBaseDir, moduleProps, moduleId);
      try {
        if (!Files.isSameFile(parentAbsBaseDir, absoluteBaseDir)) {
          tryToFindAndLoadPropsFile(absoluteBaseDir, moduleProps, moduleId);
        }
      } catch (IOException e) {
        throw new IllegalStateException("Error when resolving baseDir", e);
      }
    } else if (moduleProps.containsKey(PROPERTY_PROJECT_CONFIG_FILE)) {
      loadModulePropsFile(parentAbsBaseDir, moduleProps, moduleId);
      moduleProps.remove(PROPERTY_PROJECT_CONFIG_FILE);
    } else {
      absoluteBaseDir = parentAbsBaseDir.resolve(moduleId);
      setModuleBaseDir(absoluteBaseDir, moduleProps, moduleId);
      tryToFindAndLoadPropsFile(absoluteBaseDir, moduleProps, moduleId);
    }
  }

  private static void setModuleBaseDir(Path absoluteBaseDir, Properties childProps, String moduleId) {
    if (!Files.isDirectory(absoluteBaseDir)) {
      throw new IllegalStateException(MessageFormat
        .format("The base directory of the module ''{0}'' does not exist: {1}", moduleId, absoluteBaseDir));
    }
    childProps.put(PROPERTY_PROJECT_BASEDIR, absoluteBaseDir.toString());
  }

  protected static Properties extractModuleProperties(String module, Properties properties) {
    Properties moduleProps = new Properties();
    String propertyPrefix = module + ".";
    int prefixLength = propertyPrefix.length();
    for (Map.Entry<Object, Object> entry : properties.entrySet()) {
      String key = (String) entry.getKey();
      if (key.startsWith(propertyPrefix)) {
        moduleProps.put(key.substring(prefixLength), entry.getValue());
      }
    }
    return moduleProps;
  }

  private static Path locatePropertiesFile(Properties props) {
    Path settingsFile = null;
    String scannerHome = props.getProperty(Conf.SCANNER_HOME, "");
    if (!"".equals(scannerHome)) {
      settingsFile = Paths.get(scannerHome, "conf/sonar-scanner.properties");
    }

    return locatePropertiesFile(settingsFile, props, Conf.SCANNER_SETTINGS);
  }

  private static Path locatePropertiesFile(@Nullable Path defaultPath, Properties props, String settingsKey) {
    Path settingsFile;
    String settingsPath = props.getProperty(settingsKey, "");
    if (!"".equals(settingsPath)) {
      settingsFile = Paths.get(settingsPath);
    } else {
      settingsFile = defaultPath;
    }

    if (settingsFile != null) {
      return settingsFile.toAbsolutePath();
    }
    return null;
  }

  private static Properties toProperties(Path file) {
    Properties properties = new Properties();
    try (InputStreamReader reader = new InputStreamReader(new FileInputStream(file.toFile()), UTF_8)) {
      properties.load(reader);
      // Trim properties
      for (String propKey : properties.stringPropertyNames()) {
        properties.setProperty(propKey, properties.getProperty(propKey).trim());
      }
      return properties;
    } catch (Exception e) {
      throw new IllegalStateException("Fail to load file: " + file, e);
    }
  }

  protected void loadModulePropsFile(Path parentAbsoluteBaseDir, Properties moduleProps, String moduleId) {
    Path propertyFile = getAbsolutePath(moduleProps.getProperty(PROPERTY_PROJECT_CONFIG_FILE),
      parentAbsoluteBaseDir);
    if (Files.isRegularFile(propertyFile)) {
      moduleProps.putAll(toProperties(propertyFile));
      Path absoluteBaseDir;
      if (moduleProps.containsKey(PROPERTY_PROJECT_BASEDIR)) {
        absoluteBaseDir = getAbsolutePath(moduleProps.getProperty(PROPERTY_PROJECT_BASEDIR),
          propertyFile.getParent());
      } else {
        absoluteBaseDir = propertyFile.getParent();
      }
      setModuleBaseDir(absoluteBaseDir, moduleProps, moduleId);
    } else {
      throw new IllegalStateException(
        "The properties file of the module '" + moduleId + "' does not exist: " + propertyFile);
    }
  }

  private static void tryToFindAndLoadPropsFile(Path absoluteBaseDir, Properties moduleProps, String moduleId) {
    Path propertyFile = absoluteBaseDir.resolve(SONAR_PROJECT_PROPERTIES_FILENAME);
    if (!Files.isRegularFile(propertyFile)) {
      return;
    }

    moduleProps.putAll(toProperties(propertyFile));
    if (moduleProps.containsKey(PROPERTY_PROJECT_BASEDIR)) {
      Path overwrittenBaseDir = getAbsolutePath(moduleProps.getProperty(PROPERTY_PROJECT_BASEDIR),
        propertyFile.getParent());
      setModuleBaseDir(overwrittenBaseDir, moduleProps, moduleId);
    }
  }

  /**
   * Returns the file denoted by the given path, may this path be relative to
   * "baseDir" or absolute.
   */
  protected static Path getAbsolutePath(String path, Path baseDir) {
    Path propertyFile = Paths.get(path.trim());
    if (!propertyFile.isAbsolute()) {
      propertyFile = baseDir.resolve(propertyFile);
    }
    return propertyFile.normalize();
  }

  /**
   * Transforms a comma-separated list String property in to a array of
   * trimmed strings.
   * <p>
   * This works even if they are separated by whitespace characters (space
   * char, EOL, ...)
   */
  static String[] getListFromProperty(Properties properties, String key) {
    String value = properties.getProperty(key, "").trim();
    if (value.isEmpty()) {
      return new String[0];
    }
    String[] values = value.split(",");
    List<String> trimmedValues = new ArrayList<>();
    for (String s : values) {
      String trimmedValue = s.trim();
      if (!trimmedValue.isEmpty()) {
        trimmedValues.add(trimmedValue);
      }
    }
    return trimmedValues.toArray(new String[0]);
  }
}
