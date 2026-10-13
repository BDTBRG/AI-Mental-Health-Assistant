package com.amha.mapper;

import com.amha.entity.ConsultationSession;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface ConsultationSessionMapper extends BaseMapper<ConsultationSession> {

    @Select("SELECT * FROM consultation_session WHERE id = #{sessionId} FOR UPDATE")
    ConsultationSession selectByIdForUpdate(@Param("sessionId") Long sessionId);
}
