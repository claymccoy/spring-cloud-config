/*
 * Copyright 2013-2019 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.cloud.config.server.environment;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.model.GetObjectRequest;
import com.amazonaws.services.s3.model.S3Object;
import com.amazonaws.services.s3.model.S3ObjectIdBuilder;
import org.yaml.snakeyaml.Yaml;

import org.springframework.cloud.config.environment.Environment;
import org.springframework.cloud.config.environment.PropertySource;
import org.springframework.cloud.config.server.config.ConfigServerProperties;
import org.springframework.core.Ordered;
import org.springframework.util.StringUtils;

/**
 * @author Clay McCoy
 */
public class AwsS3EnvironmentRepository implements EnvironmentRepository, Ordered {

	private final AmazonS3 s3Client;

	private final String bucketName;

	private final ConfigServerProperties serverProperties;

	protected int order = Ordered.LOWEST_PRECEDENCE;

	public AwsS3EnvironmentRepository(AmazonS3 s3Client, String bucketName,
			ConfigServerProperties server) {
		this.s3Client = s3Client;
		this.bucketName = bucketName;
		this.serverProperties = server;
	}

	@Override
	public int getOrder() {
		return this.order;
	}

	public void setOrder(int order) {
		this.order = order;
	}

	@Override
	public Environment findOne(String specifiedApplication, String specifiedProfile,
			String specifiedLabel) {
		final String application = StringUtils.isEmpty(specifiedApplication)
				? serverProperties.getDefaultApplicationName() : specifiedApplication;
		final String profile = StringUtils.isEmpty(specifiedProfile)
				? serverProperties.getDefaultProfile() : specifiedProfile;
		final String label = StringUtils.isEmpty(specifiedLabel)
				? serverProperties.getDefaultLabel() : specifiedLabel;
		StringBuilder objectKeyPrefix = new StringBuilder(application).append("-")
				.append(profile);
		if (!StringUtils.isEmpty(label)) {
			objectKeyPrefix.append("-").append(label);
		}
		final Environment environment = new Environment(application, profile);
		environment.setLabel(label);
		final S3ObjectIdBuilder s3ObjectIdBuilder = new S3ObjectIdBuilder()
				.withBucket(bucketName);
		S3ConfigFile s3ConfigFile = getS3ConfigFile(s3ObjectIdBuilder,
				objectKeyPrefix.toString());
		if (s3ConfigFile == null) {
			throw new NoSuchRepositoryException(
					"No such repository: (" + s3ObjectIdBuilder
							.withKey(objectKeyPrefix.toString() + "(.properties | .yml)")
							.build().toString() + ")");
		}
		environment.setVersion(s3ConfigFile.getVersion());
		final Map config = s3ConfigFile.read();
		config.putAll(serverProperties.getOverrides());
		environment.add(new PropertySource(application, config));
		return environment;
	}

	private S3ConfigFile getS3ConfigFile(S3ObjectIdBuilder s3ObjectIdBuilder,
			String keyPrefix) {
		try {
			final S3Object properties = s3Client.getObject(new GetObjectRequest(
					s3ObjectIdBuilder.withKey(keyPrefix + ".properties").build()));
			return new PropertyS3ConfigFile(properties.getObjectMetadata().getVersionId(),
					properties.getObjectContent());
		}
		catch (Exception eProperties) {
			try {
				final S3Object yaml = s3Client.getObject(new GetObjectRequest(
						s3ObjectIdBuilder.withKey(keyPrefix + ".yml").build()));
				return new YamlS3ConfigFile(yaml.getObjectMetadata().getVersionId(),
						yaml.getObjectContent());
			}
			catch (Exception eYaml) {
				return null;
			}
		}
	}

}

abstract class S3ConfigFile {

	private final String version;

	protected S3ConfigFile(String version) {
		this.version = version;
	}

	String getVersion() {
		return version;
	}

	abstract Map<?, ?> read();

}

class PropertyS3ConfigFile extends S3ConfigFile {

	final InputStream inputStream;

	PropertyS3ConfigFile(String version, InputStream inputStream) {
		super(version);
		this.inputStream = inputStream;
	}

	@Override
	public Map<?, ?> read() {
		Properties props = new Properties();
		try (InputStream in = inputStream) {
			props.load(in);
		}
		catch (IOException e) {
			throw new IllegalStateException("Cannot load environment", e);
		}
		return props;
	}

}

class YamlS3ConfigFile extends S3ConfigFile {

	final InputStream inputStream;

	YamlS3ConfigFile(String version, InputStream inputStream) {
		super(version);
		this.inputStream = inputStream;
	}

	@Override
	public Map<?, ?> read() {
		final Yaml yaml = new Yaml();
		final Map<String, String> properties = new HashMap<>();
		try (InputStream in = inputStream) {
			yaml.loadAll(in).forEach((Object obj) -> this.loadValue(properties, "", obj));
			return properties;
		}
		catch (IOException e) {
			throw new IllegalStateException("Cannot load environment", e);
		}
	}

	private void loadMap(Map<String, String> properties, String baseKey,
			Map<String, ?> map) {
		map.keySet().forEach((String configKey) -> {
			final StringBuilder currentKey = new StringBuilder(baseKey);
			if (!StringUtils.isEmpty(baseKey)) {
				currentKey.append(".");
			}
			currentKey.append(configKey);
			loadValue(properties, currentKey.toString(), map.get(configKey));
		});
	}

	private void loadList(Map<String, String> properties, String baseKey, List<?> list) {
		list.forEach((Object subValue) -> {
			final StringBuilder currentKey = new StringBuilder(baseKey)
					.append("[" + list.indexOf(subValue) + "]");
			loadValue(properties, currentKey.toString(), subValue);
		});
	}

	private void loadValue(Map<String, String> properties, String currentKey,
			Object value) {
		if (value instanceof Map) {
			this.loadMap(properties, currentKey, (Map) value);
		}
		else if (value instanceof List) {
			this.loadList(properties, currentKey, (List) value);
		}
		else {
			properties.put(currentKey, value.toString());
		}
	}

}
