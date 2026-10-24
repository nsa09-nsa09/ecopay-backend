package kz.hrms.splitupauth.repository;

import java.util.List;
import java.util.Optional;
import kz.hrms.splitupauth.entity.Category;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface CategoryRepository extends JpaRepository<Category, Long> {
  List<Category> findByIsActiveTrueOrderBySortOrderAscIdAsc();

  List<Category> findAllByOrderBySortOrderAscNameAsc();

  Optional<Category> findBySlug(String slug);

  boolean existsBySlug(String slug);
}
