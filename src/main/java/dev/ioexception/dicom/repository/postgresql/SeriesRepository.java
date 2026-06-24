package dev.ioexception.dicom.repository.postgresql;

import dev.ioexception.dicom.entity.postgresql.Series;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface SeriesRepository extends JpaRepository<Series, Integer> {
}
