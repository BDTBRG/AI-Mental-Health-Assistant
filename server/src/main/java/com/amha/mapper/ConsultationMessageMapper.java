package com.amha.mapper;

import com.amha.entity.ConsultationMessage;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface ConsultationMessageMapper extends BaseMapper<ConsultationMessage> {

    @Select("SELECT MAX(id) FROM consultation_message WHERE session_id = #{sessionId}")
    Long selectMaxMessageId(@Param("sessionId") Long sessionId);

    @Select("SELECT * FROM consultation_message WHERE session_id = #{sessionId} "
            + "AND id > #{afterMessageId} AND id <= #{throughMessageId} "
            + "ORDER BY id ASC LIMIT #{limit}")
    List<ConsultationMessage> selectWindowAfterWatermark(@Param("sessionId") Long sessionId,
                                                          @Param("afterMessageId") Long afterMessageId,
                                                          @Param("throughMessageId") Long throughMessageId,
                                                          @Param("limit") int limit);
}
