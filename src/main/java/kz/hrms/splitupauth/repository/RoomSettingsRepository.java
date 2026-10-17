package kz.hrms.splitupauth.repository;

import kz.hrms.splitupauth.entity.RoomSettings;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface RoomSettingsRepository extends JpaRepository<RoomSettings, Long> {}
