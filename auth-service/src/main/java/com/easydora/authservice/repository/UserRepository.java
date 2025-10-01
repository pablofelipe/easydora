package com.easydora.authservice.repository;

import com.easydora.authservice.entity.User;
import com.easydora.authservice.entity.UserRole;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface UserRepository extends JpaRepository<User, Long> {
    
    Optional<User> findByEmail(String email);
    
    Boolean existsByEmail(String email);
    
    Long countByRole(UserRole role);
    
    @Query("SELECT u FROM User u WHERE u.email = :email AND u.status = 'ACTIVE'")
    Optional<User> findActiveUserByEmail(@Param("email") String email);
    
    @Query("SELECT COUNT(u) > 0 FROM User u WHERE u.email = :email AND u.status = 'ACTIVE'")
    Boolean existsActiveUserByEmail(@Param("email") String email);

    Optional<User> findByEmailVerificationToken(String emailVerificationToken);
}