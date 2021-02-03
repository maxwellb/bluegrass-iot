/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.deployment;

import com.amazon.aws.iot.greengrass.component.common.ComponentType;
import com.aws.greengrass.config.Configuration;
import com.aws.greengrass.config.Topic;
import com.aws.greengrass.config.Topics;
import com.aws.greengrass.dependency.Context;
import com.aws.greengrass.deployment.exceptions.ComponentConfigurationValidationException;
import com.aws.greengrass.lifecyclemanager.Kernel;
import com.aws.greengrass.testcommons.testutilities.GGExtension;
import com.aws.greengrass.util.Coerce;
import com.aws.greengrass.util.NucleusPaths;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;

import static com.aws.greengrass.componentmanager.KernelConfigResolver.VERSION_CONFIG_KEY;
import static com.aws.greengrass.deployment.DeviceConfiguration.DEFAULT_NUCLEUS_COMPONENT_NAME;
import static com.aws.greengrass.lifecyclemanager.GreengrassService.SERVICES_NAMESPACE_TOPIC;
import static com.aws.greengrass.lifecyclemanager.Kernel.SERVICE_TYPE_TOPIC_KEY;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith({MockitoExtension.class, GGExtension.class})
class DeviceConfigurationTest {
    @Mock
    Kernel mockKernel;
    @Mock
    Configuration configuration;

    DeviceConfiguration deviceConfiguration;

    @BeforeEach
    void beforeEach() {
        NucleusPaths nucleusPaths = mock(NucleusPaths.class);
        Topics rootConfigTopics = mock(Topics.class);
        when(rootConfigTopics.findOrDefault(any(), anyString(), anyString(), anyString()))
                .thenReturn(new ArrayList<>());
        when(configuration.lookup(anyString(), anyString())).thenReturn(mock(Topic.class));
        lenient().when(configuration.lookup(anyString(), anyString(), anyString())).thenReturn(mock(Topic.class));
        when(configuration.lookup(anyString(), anyString(), anyString(), anyString())).thenReturn(mock(Topic.class));
        lenient().when(configuration.getRoot()).thenReturn(rootConfigTopics);
        when(mockKernel.getConfig()).thenReturn(configuration);
        lenient().when(mockKernel.getNucleusPaths()).thenReturn(nucleusPaths);
        Topic topic = mock(Topic.class);
        Topics topics = Topics.of(mock(Context.class), SERVICES_NAMESPACE_TOPIC, mock(Topics.class));
        when(configuration.lookupTopics(anyString(), anyString(), anyString(), anyString())).thenReturn(topics);
        when(configuration.lookup(anyString(), anyString(), anyString(), anyString())).thenReturn(topic);
        lenient().when(configuration.lookupTopics(anyString())).thenReturn(topics);
    }

    @Test
    void GIVEN_good_config_WHEN_validate_THEN_succeeds() {
        deviceConfiguration = new DeviceConfiguration(mockKernel);
        assertDoesNotThrow(() -> deviceConfiguration.validateEndpoints("us-east-1", "xxxxxx.credentials.iot.us-east-1.amazonaws.com",
                "xxxxxx-ats.iot.us-east-1.amazonaws.com"));
    }

    @Test
    void GIVEN_bad_cred_endpoint_config_WHEN_validate_THEN_fails() {
        deviceConfiguration = new DeviceConfiguration(mockKernel);
        ComponentConfigurationValidationException ex = assertThrows(ComponentConfigurationValidationException.class,
                () -> deviceConfiguration.validateEndpoints("us-east-1", "xxxxxx.credentials.iot.us-east-2.amazonaws.com",
                        "xxxxxx-ats.iot.us-east-1.amazonaws.com"));
        assertEquals("IoT credential endpoint region xxxxxx.credentials.iot.us-east-2.amazonaws.com does not match the AWS region us-east-1 of the device", ex.getMessage());
    }

    @Test
    void GIVEN_bad_data_endpoint_config_WHEN_validate_THEN_fails() {
        deviceConfiguration = new DeviceConfiguration(mockKernel);
        ComponentConfigurationValidationException ex = assertThrows(ComponentConfigurationValidationException.class,
                () -> deviceConfiguration.validateEndpoints("us-east-1", "xxxxxx.credentials.iot.us-east-1.amazonaws.com",
                        "xxxxxx-ats.iot.us-east-2.amazonaws.com"));
        assertEquals("IoT data endpoint region xxxxxx-ats.iot.us-east-2.amazonaws.com does not match the AWS region us-east-1 of the device", ex.getMessage());
    }

    @Test
    void GIVEN_existing_config_including_nucleus_version_WHEN_init_device_config_THEN_use_nucleus_version_from_config()
            throws Exception {
        try (Context context = new Context()) {
            Topics servicesConfig = Topics.of(context, SERVICES_NAMESPACE_TOPIC, null);
            Topics nucleusConfig = servicesConfig.lookupTopics(DEFAULT_NUCLEUS_COMPONENT_NAME);
            Topic componentTypeConfig =
                    nucleusConfig.lookup(SERVICE_TYPE_TOPIC_KEY).withValue(ComponentType.NUCLEUS.name());
            Topic nucleusVersionConfig = nucleusConfig.lookup(VERSION_CONFIG_KEY).withValue("99.99.99");

            lenient().when(configuration.lookupTopics(SERVICES_NAMESPACE_TOPIC)).thenReturn(servicesConfig);
            lenient().when(configuration
                    .lookup(SERVICES_NAMESPACE_TOPIC, DEFAULT_NUCLEUS_COMPONENT_NAME, VERSION_CONFIG_KEY))
                    .thenReturn(nucleusVersionConfig);
            lenient().when(configuration
                    .lookup(SERVICES_NAMESPACE_TOPIC, DEFAULT_NUCLEUS_COMPONENT_NAME, SERVICE_TYPE_TOPIC_KEY))
                    .thenReturn(componentTypeConfig);
            when(mockKernel.findServiceTopic(DEFAULT_NUCLEUS_COMPONENT_NAME)).thenReturn(nucleusConfig);
            deviceConfiguration = new DeviceConfiguration(mockKernel);

            // Confirm version config didn't get overwritten with default
            assertEquals("99.99.99", Coerce.toString(nucleusVersionConfig));
            assertEquals("99.99.99", deviceConfiguration.getNucleusVersion());
        }

    }

    @Test
    void GIVEN_existing_config_with_no_nucleus_version_WHEN_init_device_config_THEN_use_default_nucleus_version()
            throws Exception {
        try (Context context = new Context()) {
            Topics servicesConfig = Topics.of(context, SERVICES_NAMESPACE_TOPIC, null);
            Topics nucleusConfig = servicesConfig.lookupTopics(DEFAULT_NUCLEUS_COMPONENT_NAME);
            Topic componentTypeConfig =
                    nucleusConfig.lookup(SERVICE_TYPE_TOPIC_KEY).withValue(ComponentType.NUCLEUS.name());

            lenient().when(configuration.lookupTopics(SERVICES_NAMESPACE_TOPIC)).thenReturn(servicesConfig);
            lenient().when(configuration
                    .lookup(SERVICES_NAMESPACE_TOPIC, DEFAULT_NUCLEUS_COMPONENT_NAME, SERVICE_TYPE_TOPIC_KEY))
                    .thenReturn(componentTypeConfig);
            when(mockKernel.findServiceTopic(DEFAULT_NUCLEUS_COMPONENT_NAME)).thenReturn(nucleusConfig);

            deviceConfiguration = new DeviceConfiguration(mockKernel);

            // Expect fallback version in the absence of version information from build files
            assertEquals("0.0.0", deviceConfiguration.getNucleusVersion());
        }
    }

    @Test
    void GIVEN_no_existing_config_WHEN_init_device_config_THEN_use_default_nucleus_config() throws Exception {
        try (Context context = new Context()) {
            Topics servicesConfig = Topics.of(context, SERVICES_NAMESPACE_TOPIC, null);

            lenient().when(configuration.lookupTopics(SERVICES_NAMESPACE_TOPIC)).thenReturn(servicesConfig);

            deviceConfiguration = new DeviceConfiguration(mockKernel);

            when(mockKernel.findServiceTopic(DEFAULT_NUCLEUS_COMPONENT_NAME))
                    .thenReturn(servicesConfig.findTopics(DEFAULT_NUCLEUS_COMPONENT_NAME));

            // Expect fallback version in the absence of version information from build files
            assertEquals("0.0.0", deviceConfiguration.getNucleusVersion());
        }
    }
}
