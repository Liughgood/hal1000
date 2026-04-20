package com.genhao.hal1000.persistence.repo;

import com.genhao.hal1000.persistence.entity.AppUserEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AppUserRepository extends JpaRepository<AppUserEntity, String> {

    Optional<AppUserEntity> findByGithubId(Long githubId);
}

