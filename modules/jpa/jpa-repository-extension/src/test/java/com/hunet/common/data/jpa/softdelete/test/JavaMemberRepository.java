package com.hunet.common.data.jpa.softdelete.test;

import com.hunet.common.data.jpa.softdelete.SoftDeleteJpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface JavaMemberRepository extends SoftDeleteJpaRepository<JavaMember, Long> {
}

