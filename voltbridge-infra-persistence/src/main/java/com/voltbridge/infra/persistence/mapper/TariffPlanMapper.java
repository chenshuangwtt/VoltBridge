package com.voltbridge.infra.persistence.mapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.voltbridge.infra.persistence.entity.TariffPlanEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
/** 资费方案基础 Mapper。 */
@Mapper public interface TariffPlanMapper extends BaseMapper<TariffPlanEntity> {
    @Select("WITH acquired AS (SELECT pg_advisory_xact_lock(hashtextextended(#{lockKey}, 0))) SELECT true FROM acquired")
    boolean lockScope(@Param("lockKey") String lockKey);

    @Insert("""
            INSERT INTO tariff_revision_counter (family_id, last_revision)
            VALUES (#{familyId}, #{revision})
            ON CONFLICT (family_id) DO UPDATE
            SET last_revision = GREATEST(tariff_revision_counter.last_revision, EXCLUDED.last_revision)
            """)
    int reserveRevision(@Param("familyId") java.util.UUID familyId,
                        @Param("revision") int revision);

    @Insert("""
            INSERT INTO tariff_revision_counter (family_id, last_revision)
            SELECT #{familyId}, COALESCE(MAX(revision), 0)
            FROM tariff_plan WHERE family_id = #{familyId}
            ON CONFLICT (family_id) DO NOTHING
            """)
    int initializeRevisionCounter(@Param("familyId") java.util.UUID familyId);

    @Update("UPDATE tariff_revision_counter SET last_revision = last_revision + 1 WHERE family_id = #{familyId}")
    int incrementRevisionCounter(@Param("familyId") java.util.UUID familyId);

    @Select("SELECT last_revision FROM tariff_revision_counter WHERE family_id = #{familyId}")
    int revisionCounter(@Param("familyId") java.util.UUID familyId);
}
