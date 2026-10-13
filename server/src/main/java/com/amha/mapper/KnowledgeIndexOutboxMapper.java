package com.amha.mapper;

import com.amha.entity.KnowledgeIndexOutbox;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface KnowledgeIndexOutboxMapper extends BaseMapper<KnowledgeIndexOutbox> {

    @Select("SELECT * FROM knowledge_index_outbox "
            + "WHERE (status IN ('PENDING', 'DEAD') AND next_attempt_at <= CURRENT_TIMESTAMP) "
            + "OR (status IN ('QUEUED', 'PROCESSING') AND lease_until <= CURRENT_TIMESTAMP) "
            + "ORDER BY id LIMIT 1 FOR UPDATE SKIP LOCKED")
    KnowledgeIndexOutbox selectNextReadyForUpdate();

    @Select("SELECT * FROM knowledge_index_outbox WHERE id = #{id} FOR UPDATE")
    KnowledgeIndexOutbox selectByIdForUpdate(@Param("id") Long id);

    @Select("SELECT * FROM knowledge_index_outbox WHERE article_id = #{articleId} FOR UPDATE")
    KnowledgeIndexOutbox selectByArticleIdForUpdate(@Param("articleId") Long articleId);
}
