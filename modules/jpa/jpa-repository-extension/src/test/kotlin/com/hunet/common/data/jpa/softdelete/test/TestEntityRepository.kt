package com.hunet.common.data.jpa.softdelete.test

import com.hunet.common.data.jpa.softdelete.SoftDeleteJpaRepository

interface TestEntityRepository : SoftDeleteJpaRepository<TestEntity, Long>

