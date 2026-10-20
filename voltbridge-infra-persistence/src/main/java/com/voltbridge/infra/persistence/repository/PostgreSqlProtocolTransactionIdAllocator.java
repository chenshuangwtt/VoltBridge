package com.voltbridge.infra.persistence.repository;

import com.voltbridge.business.domain.transaction.ProtocolTransactionIdAllocator;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 使用 PostgreSQL 序列持久化分配设备协议需要的正整数交易流水。 */
@Repository
public class PostgreSqlProtocolTransactionIdAllocator implements ProtocolTransactionIdAllocator {

    private final JdbcTemplate jdbcTemplate;

    public PostgreSqlProtocolTransactionIdAllocator(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public long nextId() {
        Long value = jdbcTemplate.queryForObject(
                "SELECT nextval('protocol_transaction_id_seq')", Long.class);
        if (value == null || value > Integer.MAX_VALUE) {
            throw new IllegalStateException("Protocol transaction id range exhausted");
        }
        return value;
    }
}
