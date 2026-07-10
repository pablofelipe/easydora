package com.easydora.orders.repository;

import com.easydora.orders.entity.ProductOwnership;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ProductOwnershipRepository extends JpaRepository<ProductOwnership, String> {
}
