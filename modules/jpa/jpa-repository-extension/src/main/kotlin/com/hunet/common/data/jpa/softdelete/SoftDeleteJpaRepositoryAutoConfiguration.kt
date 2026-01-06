package com.hunet.common.data.jpa.softdelete

import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration
import org.springframework.data.jpa.repository.config.EnableJpaRepositories

@Configuration
@EnableConfigurationProperties(SoftDeleteProperties::class)
@EnableJpaRepositories(repositoryBaseClass = SoftDeleteJpaRepositoryImpl::class)
class SoftDeleteJpaRepositoryAutoConfiguration

