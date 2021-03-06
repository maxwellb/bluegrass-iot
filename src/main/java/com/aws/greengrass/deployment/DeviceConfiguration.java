/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.deployment;

import com.amazon.aws.iot.greengrass.component.common.ComponentType;
import com.aws.greengrass.config.CaseInsensitiveString;
import com.aws.greengrass.config.ChildChanged;
import com.aws.greengrass.config.Node;
import com.aws.greengrass.config.Topic;
import com.aws.greengrass.config.Topics;
import com.aws.greengrass.config.Validator;
import com.aws.greengrass.config.WhatHappened;
import com.aws.greengrass.deployment.exceptions.ComponentConfigurationValidationException;
import com.aws.greengrass.deployment.exceptions.DeviceConfigurationException;
import com.aws.greengrass.lifecyclemanager.Kernel;
import com.aws.greengrass.logging.api.Logger;
import com.aws.greengrass.logging.impl.LogManager;
import com.aws.greengrass.logging.impl.config.LogFormat;
import com.aws.greengrass.logging.impl.config.LogStore;
import com.aws.greengrass.logging.impl.config.model.LoggerConfiguration;
import com.aws.greengrass.util.Coerce;
import com.aws.greengrass.util.Utils;
import com.aws.greengrass.util.platforms.Platform;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.slf4j.event.Level;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.regions.providers.DefaultAwsRegionProviderChain;

import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import javax.inject.Inject;

import static com.aws.greengrass.componentmanager.KernelConfigResolver.CONFIGURATION_CONFIG_KEY;
import static com.aws.greengrass.componentmanager.KernelConfigResolver.VERSION_CONFIG_KEY;
import static com.aws.greengrass.lifecyclemanager.GreengrassService.SERVICES_NAMESPACE_TOPIC;
import static com.aws.greengrass.lifecyclemanager.GreengrassService.SERVICE_DEPENDENCIES_NAMESPACE_TOPIC;
import static com.aws.greengrass.lifecyclemanager.GreengrassService.SETENV_CONFIG_NAMESPACE;
import static com.aws.greengrass.lifecyclemanager.Kernel.SERVICE_TYPE_TOPIC_KEY;
import static com.aws.greengrass.lifecyclemanager.KernelAlternatives.locateCurrentKernelUnpackDir;
import static com.aws.greengrass.lifecyclemanager.KernelCommandLine.MAIN_SERVICE_NAME;

/**
 * Class for providing device configuration information.
 */
@SuppressWarnings("PMD.DataClass")
@SuppressFBWarnings("IS2_INCONSISTENT_SYNC")
public class DeviceConfiguration {

    public static final String DEFAULT_NUCLEUS_COMPONENT_NAME = "aws.greengrass.Nucleus";

    public static final String DEVICE_PARAM_THING_NAME = "thingName";
    public static final String DEVICE_PARAM_IOT_DATA_ENDPOINT = "iotDataEndpoint";
    public static final String DEVICE_PARAM_IOT_CRED_ENDPOINT = "iotCredEndpoint";
    public static final String DEVICE_PARAM_PRIVATE_KEY_PATH = "privateKeyPath";
    public static final String DEVICE_PARAM_CERTIFICATE_FILE_PATH = "certificateFilePath";
    public static final String DEVICE_PARAM_ROOT_CA_PATH = "rootCaPath";
    public static final String SYSTEM_NAMESPACE_KEY = "system";
    public static final String PLATFORM_OVERRIDE_TOPIC = "platformOverride";
    public static final String DEVICE_PARAM_AWS_REGION = "awsRegion";
    public static final String DEVICE_MQTT_NAMESPACE = "mqtt";
    public static final String DEVICE_SPOOLER_NAMESPACE = "spooler";
    public static final String RUN_WITH_TOPIC = "runWithDefault";
    public static final String RUN_WITH_DEFAULT_POSIX_USER = "posixUser";
    public static final String RUN_WITH_DEFAULT_WINDOWS_USER = "windowsUser";
    public static final String RUN_WITH_DEFAULT_POSIX_SHELL = "posixShell";
    public static final String RUN_WITH_DEFAULT_POSIX_SHELL_VALUE = "sh";

    public static final String IOT_ROLE_ALIAS_TOPIC = "iotRoleAlias";
    public static final String COMPONENT_STORE_MAX_SIZE_BYTES = "componentStoreMaxSizeBytes";
    public static final String DEPLOYMENT_POLLING_FREQUENCY_SECONDS = "deploymentPollingFrequencySeconds";
    public static final String NUCLEUS_CONFIG_LOGGING_TOPICS = "logging";
    public static final String TELEMETRY_CONFIG_LOGGING_TOPICS = "telemetry";

    public static final String DEVICE_NETWORK_PROXY_NAMESPACE = "networkProxy";
    public static final String DEVICE_PROXY_NAMESPACE = "proxy";
    public static final String DEVICE_PARAM_NO_PROXY_ADDRESSES = "noProxyAddresses";
    public static final String DEVICE_PARAM_PROXY_URL = "url";
    public static final String DEVICE_PARAM_PROXY_USERNAME = "username";
    public static final String DEVICE_PARAM_PROXY_PASSWORD = "password";
    public static final long COMPONENT_STORE_MAX_SIZE_DEFAULT_BYTES = 10_000_000_000L;
    public static final long DEPLOYMENT_POLLING_FREQUENCY_DEFAULT_SECONDS = 15L;
    public static final String DEVICE_PARAM_GG_DATA_PLANE_PORT = "greengrassDataPlanePort";
    private static final int GG_DATA_PLANE_PORT_DEFAULT = 8443;

    private static final String DEVICE_PARAM_ENV_STAGE = "envStage";
    private static final String DEFAULT_ENV_STAGE = "prod";
    private static final String CANNOT_BE_EMPTY = " cannot be empty";
    private static final Logger logger = LogManager.getLogger(DeviceConfiguration.class);
    private static final String FALLBACK_DEFAULT_REGION = "us-east-1";
    public static final String AWS_IOT_THING_NAME_ENV = "AWS_IOT_THING_NAME";
    public static final String GGC_VERSION_ENV = "GGC_VERSION";
    protected static final String NUCLEUS_VERSION_BUILD_METADATA_KEY = "nucleus.version";
    protected static final String NUCLEUS_BUILD_METADATA_FILENAME = "nucleus-build.properties";
    protected static final String NUCLEUS_BUILD_METADATA_DIRECTORY = "conf";
    protected static final String FALLBACK_VERSION = "0.0.0";
    private final Kernel kernel;

    private final Validator deTildeValidator;
    private final Validator regionValidator;

    private Topics loggingTopics;
    private LoggerConfiguration currentConfiguration;
    private String nucleusComponentNameCache;
    private static final Properties NUCLEUS_BUILD_PROPERTIES = new Properties();

    /**
     * Constructor used to read device configuration from the config store.
     *
     * @param kernel Kernel to get config from
     */
    @Inject
    public DeviceConfiguration(Kernel kernel) {
        this.kernel = kernel;
        deTildeValidator = getDeTildeValidator();
        regionValidator = getRegionValidator();
        handleLoggingConfig();

        getComponentStoreMaxSizeBytes().dflt(COMPONENT_STORE_MAX_SIZE_DEFAULT_BYTES);
        getDeploymentPollingFrequencySeconds().dflt(DEPLOYMENT_POLLING_FREQUENCY_DEFAULT_SECONDS);

        kernel.getConfig().lookup(SETENV_CONFIG_NAMESPACE, GGC_VERSION_ENV).withValue(getNucleusVersion());
    }

    /**
     * Constructor to use when setting the device configuration to kernel config.
     *
     * @param kernel              kernel to set config for
     * @param thingName           IoT thing name
     * @param iotDataEndpoint     IoT data endpoint
     * @param iotCredEndpoint     IoT cert endpoint
     * @param privateKeyPath      private key location on device
     * @param certificateFilePath certificate location on device
     * @param rootCaFilePath      downloaded RootCA location on device
     * @param awsRegion           aws region for the device
     * @param tesRoleAliasName    aws region for the device
     * @throws DeviceConfigurationException when the configuration parameters are not valid
     */
    public DeviceConfiguration(Kernel kernel, String thingName, String iotDataEndpoint, String iotCredEndpoint,
                               String privateKeyPath, String certificateFilePath, String rootCaFilePath,
                               String awsRegion, String tesRoleAliasName) throws DeviceConfigurationException {
        this(kernel);
        getThingName().withValue(thingName);
        getIotDataEndpoint().withValue(iotDataEndpoint);
        getIotCredentialEndpoint().withValue(iotCredEndpoint);
        getPrivateKeyFilePath().withValue(privateKeyPath);
        getCertificateFilePath().withValue(certificateFilePath);
        getRootCAFilePath().withValue(rootCaFilePath);
        getAWSRegion().withValue(awsRegion);
        getIotRoleAlias().withValue(tesRoleAliasName);

        validate();
    }

    /**
     * Get the Nucleus component's name.
     *
     * @return Nucleus component name
     */
    public synchronized String getNucleusComponentName() {
        // Check to see if the nucleus is still present in the config. If it isn't present, then
        // recalculate the component's name
        if (nucleusComponentNameCache == null || kernel.findServiceTopic(nucleusComponentNameCache) == null) {
            nucleusComponentNameCache = initNucleusComponentName();
        }
        return nucleusComponentNameCache;
    }

    /**
     * Get the logging configuration.
     *
     * @return Configuration for logger.
     */
    public Topics getLoggingConfigurationTopics() {
        return getTopics(NUCLEUS_CONFIG_LOGGING_TOPICS);
    }

    /**
     * Get the telemetry configuration.
     *
     * @return Configuration for telemetry agent.
     */
    public Topics getTelemetryConfigurationTopics() {
        return getTopics(TELEMETRY_CONFIG_LOGGING_TOPICS);
    }

    /**
     * Get the Nucleus component name to lookup the configuration in the right place. If no component of type Nucleus
     * exists, create service config for the default Nucleus component.
     */
    private String initNucleusComponentName() {
        Optional<CaseInsensitiveString> nucleusComponent =
                kernel.getConfig().lookupTopics(SERVICES_NAMESPACE_TOPIC).children.keySet().stream()
                        .filter(s -> ComponentType.NUCLEUS.name().equals(getComponentType(s.toString())))
                        .findAny();
        if (nucleusComponent.isPresent()) {
            return nucleusComponent.get().toString();
        } else {
            initializeNucleusComponentConfig();
            return DEFAULT_NUCLEUS_COMPONENT_NAME;
        }
    }

    private void initializeNucleusComponentConfig() {
        kernel.getConfig().lookup(SERVICES_NAMESPACE_TOPIC, DEFAULT_NUCLEUS_COMPONENT_NAME, SERVICE_TYPE_TOPIC_KEY)
                .withValue(ComponentType.NUCLEUS.name());
        kernel.getConfig().lookup(SERVICES_NAMESPACE_TOPIC, DEFAULT_NUCLEUS_COMPONENT_NAME, VERSION_CONFIG_KEY)
                .dflt(getVersionFromBuildMetadataFile());
        ArrayList<String> mainDependencies = (ArrayList) kernel.getConfig().getRoot()
                .findOrDefault(new ArrayList<>(), SERVICES_NAMESPACE_TOPIC, MAIN_SERVICE_NAME,
                        SERVICE_DEPENDENCIES_NAMESPACE_TOPIC);
        mainDependencies.add(DEFAULT_NUCLEUS_COMPONENT_NAME);
        kernel.getConfig().lookup(SERVICES_NAMESPACE_TOPIC, MAIN_SERVICE_NAME, SERVICE_DEPENDENCIES_NAMESPACE_TOPIC)
                .dflt(mainDependencies);
    }

    /**
     * Handles subscribing and reconfiguring logger based on the correct topic.
     */
    private void handleLoggingConfig() {
        loggingTopics = getLoggingConfigurationTopics();
        loggingTopics.subscribe(this::handleLoggingConfigurationChanges);
    }

    /**
     * Handle logging configuration changes.
     *
     * @param what         What changed.
     * @param loggingParam which logging param changed topic.
     */
    @SuppressWarnings("PMD.UselessParentheses")
    public synchronized void handleLoggingConfigurationChanges(WhatHappened what, Node loggingParam) {
        LoggerConfiguration configuration;
        try {
            configuration = fromPojo(loggingTopics.toPOJO());
            LogManager.setEffectiveConfig(configuration);
        } catch (IllegalArgumentException e) {
            logger.atError().kv("logging-config", loggingTopics).cause(e).log("Unable to parse logging config.");
            return;
        }
        if (currentConfiguration == null || !currentConfiguration.equals(configuration)) {
            if (configuration.getOutputDirectory() != null
                    && (currentConfiguration == null || !Objects.equals(currentConfiguration.getOutputDirectory(),
                    configuration.getOutputDirectory()))) {
                try {
                    kernel.getNucleusPaths().setLoggerPath(Paths.get(configuration.getOutputDirectory()));
                } catch (IOException e) {
                    logger.atError().cause(e).log("Unable to initialize logger output directory path");
                }
            }
            currentConfiguration = configuration;
            LogManager.reconfigureAllLoggers(configuration);
        }
    }

    private String getComponentType(String serviceName) {
        return Coerce.toString(kernel.getConfig().find(SERVICES_NAMESPACE_TOPIC, serviceName, SERVICE_TYPE_TOPIC_KEY));
    }

    private Validator getDeTildeValidator() {
        return (newV, old) -> kernel.deTilde(Coerce.toString(newV));
    }

    @SuppressWarnings("PMD.NullAssignment")
    private Validator getRegionValidator() {
        return (newV, old) -> {
            String region = null;
            if (newV == null || newV instanceof String) {
                region = (String) newV;
            }

            // If the region value is empty/null, then try to get the region from the SDK lookup path
            if (!(newV instanceof String) || Utils.isEmpty(region)) {
                try {
                    region = DefaultAwsRegionProviderChain.builder().build().getRegion().toString();
                } catch (SdkClientException ex) {
                    region = null;
                    logger.atWarn().log("Error looking up AWS region", ex);
                }
            }
            // Snow* devices have a null region
            if (Utils.isEmpty(region) || "null".equals(region)) {
                logger.atWarn().log("No AWS region found, falling back to default: {}", FALLBACK_DEFAULT_REGION);
                region = FALLBACK_DEFAULT_REGION;
            }

            kernel.getConfig().lookup(SETENV_CONFIG_NAMESPACE, "AWS_DEFAULT_REGION").withValue(region);
            kernel.getConfig().lookup(SETENV_CONFIG_NAMESPACE, "AWS_REGION").withValue(region);

            return region;
        };
    }

    public Topics getRunWithTopic() {
        return getTopics(RUN_WITH_TOPIC);
    }

    public Topic getRunWithDefaultPosixUser() {
        return getRunWithTopic().lookup(RUN_WITH_DEFAULT_POSIX_USER);
    }

    public Topic getRunWithDefaultPosixShell() {
        return getRunWithTopic().lookup(RUN_WITH_DEFAULT_POSIX_SHELL).dflt(RUN_WITH_DEFAULT_POSIX_SHELL_VALUE);
    }

    public Topic getRunWithDefaultWindowsUser() {
        return getRunWithTopic().lookup(RUN_WITH_DEFAULT_WINDOWS_USER);
    }

    /**
     * Topic containing a set of key/value describing a platform. If provided, overrides (part of) the detected
     * platform.
     *
     * @return Platform override topic
     */
    public Topics getPlatformOverrideTopic() {
        return getTopics(PLATFORM_OVERRIDE_TOPIC);
    }

    /**
     * Get thing name configuration. Also adds the thing name to the env vars if it has changed.
     *
     * @return Thing name config topic.
     */
    public Topic getThingName() {
        Topic thingNameTopic = kernel.getConfig().lookup(SYSTEM_NAMESPACE_KEY, DEVICE_PARAM_THING_NAME).dflt("");
        kernel.getConfig().lookup(SETENV_CONFIG_NAMESPACE, AWS_IOT_THING_NAME_ENV)
                .withValue(Coerce.toString(thingNameTopic));
        return thingNameTopic;
    }

    public Topic getCertificateFilePath() {
        return kernel.getConfig().lookup(SYSTEM_NAMESPACE_KEY, DEVICE_PARAM_CERTIFICATE_FILE_PATH).dflt("")
                .addValidator(deTildeValidator);
    }

    public Topic getPrivateKeyFilePath() {
        return kernel.getConfig().lookup(SYSTEM_NAMESPACE_KEY, DEVICE_PARAM_PRIVATE_KEY_PATH).dflt("")
                .addValidator(deTildeValidator);
    }

    public Topic getRootCAFilePath() {
        return kernel.getConfig().lookup(SYSTEM_NAMESPACE_KEY, DEVICE_PARAM_ROOT_CA_PATH).dflt("")
                .addValidator(deTildeValidator);
    }

    public Topic getIotDataEndpoint() {
        return getTopic(DEVICE_PARAM_IOT_DATA_ENDPOINT).dflt("");
    }

    public Topic getIotCredentialEndpoint() {
        return getTopic(DEVICE_PARAM_IOT_CRED_ENDPOINT).dflt("");
    }

    public Topic getAWSRegion() {
        return getTopic(DEVICE_PARAM_AWS_REGION).dflt("").addValidator(regionValidator);
    }

    public Topic getGreengrassDataPlanePort() {
        return getTopic(DEVICE_PARAM_GG_DATA_PLANE_PORT).dflt(GG_DATA_PLANE_PORT_DEFAULT);
    }

    // Why have this method as well as the one above? The reason is that the validator
    // is called immediately, so the initial call will have a null region which will make
    // the validator use the default region provider chain to do a lookup which isn't necessary.
    public void setAWSRegion(String region) {
        getTopic(DEVICE_PARAM_AWS_REGION).withValue(region).addValidator(regionValidator);
    }

    public Topic getEnvironmentStage() {
        return getTopic(DEVICE_PARAM_ENV_STAGE).withNewerValue(1, DEFAULT_ENV_STAGE);
    }

    public Topics getMQTTNamespace() {
        return getTopics(DEVICE_MQTT_NAMESPACE);
    }

    public Topics getSpoolerNamespace() {
        return getMQTTNamespace().lookupTopics(DEVICE_SPOOLER_NAMESPACE);
    }

    public Topics getNetworkProxyNamespace() {
        return getTopics(DEVICE_NETWORK_PROXY_NAMESPACE);
    }

    public Topics getProxyNamespace() {
        return getNetworkProxyNamespace().lookupTopics(DEVICE_PROXY_NAMESPACE);
    }

    public String getNoProxyAddresses() {
        return Coerce.toString(getNetworkProxyNamespace().findOrDefault("", DEVICE_PARAM_NO_PROXY_ADDRESSES));
    }

    public String getProxyUrl() {
        return Coerce.toString(getProxyNamespace().findOrDefault("", DEVICE_PARAM_PROXY_URL));
    }

    public String getProxyUsername() {
        return Coerce.toString(getProxyNamespace().findOrDefault("", DEVICE_PARAM_PROXY_USERNAME));
    }

    public String getProxyPassword() {
        return Coerce.toString(getProxyNamespace().findOrDefault("", DEVICE_PARAM_PROXY_PASSWORD));
    }

    public Topic getIotRoleAlias() {
        return getTopic(IOT_ROLE_ALIAS_TOPIC).dflt("");
    }

    public Topic getComponentStoreMaxSizeBytes() {
        return getTopic(COMPONENT_STORE_MAX_SIZE_BYTES);
    }

    public Topic getDeploymentPollingFrequencySeconds() {
        return getTopic(DEPLOYMENT_POLLING_FREQUENCY_SECONDS);
    }

    /**
     * Subscribe to all device configuration change.
     *
     * @param cc Subscribe handler
     */
    public void onAnyChange(ChildChanged cc) {
        kernel.getConfig().lookupTopics(SERVICES_NAMESPACE_TOPIC, getNucleusComponentName(), CONFIGURATION_CONFIG_KEY)
                .subscribe(cc);
        kernel.getConfig().lookupTopics(SYSTEM_NAMESPACE_KEY).subscribe(cc);
    }

    /**
     * Validates the device configuration parameters.
     *
     * @throws DeviceConfigurationException when configuration parameters are not valid
     */
    public void validate() throws DeviceConfigurationException {
        String thingName = Coerce.toString(getThingName());
        String certificateFilePath = Coerce.toString(getCertificateFilePath());
        String privateKeyPath = Coerce.toString(getPrivateKeyFilePath());
        String rootCAPath = Coerce.toString(getRootCAFilePath());
        String iotDataEndpoint = Coerce.toString(getIotDataEndpoint());
        String iotCredEndpoint = Coerce.toString(getIotCredentialEndpoint());
        String awsRegion = Coerce.toString(getAWSRegion());

        validateDeviceConfiguration(thingName, certificateFilePath, privateKeyPath, rootCAPath, iotDataEndpoint,
                iotCredEndpoint, awsRegion);
    }

    /**
     * Check if device is configured to talk to cloud.
     *
     * @return true is device configuration is valid
     */
    public boolean isDeviceConfiguredToTalkToCloud() {
        try {
            validate();
            return true;
        } catch (DeviceConfigurationException e) {
            return false;
        }

    }

    private Topic getTopic(String parameterName) {
        return kernel.getConfig()
                .lookup(SERVICES_NAMESPACE_TOPIC, getNucleusComponentName(), CONFIGURATION_CONFIG_KEY, parameterName);
    }

    private Topics getTopics(String parameterName) {
        return kernel.getConfig()
                .lookupTopics(SERVICES_NAMESPACE_TOPIC, getNucleusComponentName(),
                        CONFIGURATION_CONFIG_KEY, parameterName);
    }

    /**
     * Get the nucleus version from the running configuration or nucleus zip.
     *
     * @return nucleus version
     */
    public String getNucleusVersion() {
        String version = null;
        // Prefer to get the version from the active config
        Topics componentTopic = kernel.findServiceTopic(getNucleusComponentName());
        if (componentTopic != null && componentTopic.find(VERSION_CONFIG_KEY) != null) {
            version = Coerce.toString(componentTopic.find(VERSION_CONFIG_KEY));
        }
        if (version == null) {
            return getVersionFromBuildMetadataFile();
        } else {
            return version;
        }
    }

    /**
     * Get the Nucleus version from the ZIP file.
     *
     * @return version from the zip file, or a default if the version can't be determined
     */
    public static String getVersionFromBuildMetadataFile() {
        try {
            try (InputStream is = Files
                    .newInputStream(locateCurrentKernelUnpackDir().resolve(NUCLEUS_BUILD_METADATA_DIRECTORY)
                            .resolve(NUCLEUS_BUILD_METADATA_FILENAME))) {
                NUCLEUS_BUILD_PROPERTIES.load(is);
            }

            String version = NUCLEUS_BUILD_PROPERTIES.getProperty(NUCLEUS_VERSION_BUILD_METADATA_KEY);
            if (version != null) {
                return version;
            }
        } catch (IOException | URISyntaxException e) {
            logger.atError().log("Unable to determine Greengrass version", e);
        }
        logger.atError().log("Unable to determine Greengrass version from build metadata file. "
                + "Build file not found, or version not found in file. Falling back to {}", FALLBACK_VERSION);
        return FALLBACK_VERSION;
    }

    private void validateDeviceConfiguration(String thingName, String certificateFilePath, String privateKeyPath,
                                             String rootCAPath, String iotDataEndpoint, String iotCredEndpoint,
                                             String awsRegion)
            throws DeviceConfigurationException {
        List<String> errors = new ArrayList<>();
        if (Utils.isEmpty(thingName)) {
            errors.add(DEVICE_PARAM_THING_NAME + CANNOT_BE_EMPTY);
        }
        if (Utils.isEmpty(certificateFilePath)) {
            errors.add(DEVICE_PARAM_CERTIFICATE_FILE_PATH + CANNOT_BE_EMPTY);
        }
        if (Utils.isEmpty(privateKeyPath)) {
            errors.add(DEVICE_PARAM_PRIVATE_KEY_PATH + CANNOT_BE_EMPTY);
        }
        if (Utils.isEmpty(rootCAPath)) {
            errors.add(DEVICE_PARAM_ROOT_CA_PATH + CANNOT_BE_EMPTY);
        }
        if (Utils.isEmpty(iotDataEndpoint)) {
            errors.add(DEVICE_PARAM_IOT_DATA_ENDPOINT + CANNOT_BE_EMPTY);
        }
        if (Utils.isEmpty(iotCredEndpoint)) {
            errors.add(DEVICE_PARAM_IOT_CRED_ENDPOINT + CANNOT_BE_EMPTY);
        }
        if (Utils.isEmpty(awsRegion)) {
            errors.add(DEVICE_PARAM_AWS_REGION + CANNOT_BE_EMPTY);
        }

        try {
            validateEndpoints(awsRegion, iotCredEndpoint, iotDataEndpoint);
            Platform.getInstance().getRunWithGenerator().validateDefaultConfiguration(this);
        } catch (DeviceConfigurationException | ComponentConfigurationValidationException e) {
            errors.add(e.getMessage());
        }
        if (!errors.isEmpty()) {
            throw new DeviceConfigurationException(errors.toString());
        }
    }

    /**
     * Validate the IoT credential and data endpoint with the provided AWS region. Currently it checks that the if the
     * endpoints are provided, then the AWS region should be a part of the URL.
     *
     * @param awsRegion       the provided AWS region.
     * @param iotCredEndpoint the provided IoT credentials endpoint
     * @param iotDataEndpoint the providedIoT data endpoint
     * @throws ComponentConfigurationValidationException if the region is not valid or if the IoT endpoints do not have
     *                                                   the AWS region as a part of its URL.
     */
    public void validateEndpoints(String awsRegion, String iotCredEndpoint, String iotDataEndpoint)
            throws ComponentConfigurationValidationException {
        if (Utils.isNotEmpty(awsRegion) && !Region.regions().contains(Region.of(awsRegion))) {
            logger.atWarn().log("Error looking up AWS region {}", awsRegion);
            throw new ComponentConfigurationValidationException(String.format("Error looking up AWS region %s",
                    awsRegion));
        }
        if (Utils.isNotEmpty(iotCredEndpoint) && !iotCredEndpoint.contains(awsRegion)) {
            throw new ComponentConfigurationValidationException(
                    String.format("IoT credential endpoint region %s does not match the AWS region %s of the device",
                            iotCredEndpoint, awsRegion));
        }
        if (Utils.isNotEmpty(iotDataEndpoint) && !iotDataEndpoint.contains(awsRegion)) {
            throw new ComponentConfigurationValidationException(
                    String.format("IoT data endpoint region %s does not match the AWS region %s of the device",
                            iotDataEndpoint, awsRegion));
        }
    }

    /**
     * Get the logger configuration from POJO.
     *
     * @param pojoMap The map containing logger configuration.
     * @return the logger configuration.
     * @throws IllegalArgumentException if the POJO map has an invalid argument.
     */
    private LoggerConfiguration fromPojo(Map<String, Object> pojoMap) {
        LoggerConfiguration configuration = LoggerConfiguration.builder().build();
        pojoMap.forEach((s, o) -> {
            switch (s) {
                case "level":
                    configuration.setLevel(Level.valueOf(Coerce.toString(o)));
                    break;
                case "fileSizeKB":
                    configuration.setFileSizeKB(Coerce.toLong(o));
                    break;
                case "totalLogsSizeKB":
                    configuration.setTotalLogsSizeKB(Coerce.toLong(o));
                    break;
                case "format":
                    configuration.setFormat(LogFormat.valueOf(Coerce.toString(o)));
                    break;
                case "outputDirectory":
                    configuration.setOutputDirectory(Coerce.toString(o));
                    break;
                case "outputType":
                    configuration.setOutputType(LogStore.valueOf(Coerce.toString(o)));
                    break;
                default:
                    throw new IllegalArgumentException("Unexpected value: " + s);
            }
        });
        return configuration;
    }
}
