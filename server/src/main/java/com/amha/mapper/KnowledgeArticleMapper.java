package com.amha.mapper;

import com.amha.entity.KnowledgeArticle;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface KnowledgeArticleMapper extends BaseMapper<KnowledgeArticle> {

    @Select("SELECT id, title, content FROM knowledge_article WHERE status = 1 ORDER BY id ASC")
    List<KnowledgeArticle> selectPublishedForIndex();
}
