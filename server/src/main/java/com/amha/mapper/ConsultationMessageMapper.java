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

    @Select("SELECT MAX(m.id) FROM consultation_message m "
            + "JOIN consultation_session s ON s.id = m.session_id "
            + "WHERE m.session_id = #{sessionId} AND s.user_id = #{userId}")
    Long selectMaxMessageIdForOwner(@Param("sessionId") Long sessionId,
                                   @Param("userId") Long userId);

    @Select("SELECT m.* FROM consultation_message m "
            + "JOIN consultation_session s ON s.id = m.session_id "
            + "WHERE m.session_id = #{sessionId} AND s.user_id = #{userId} "
            + "AND m.id > #{afterMessageId} ORDER BY m.id DESC LIMIT #{limit}")
    List<ConsultationMessage> selectLatestAfterWatermarkForOwner(@Param("sessionId") Long sessionId,
                                                                  @Param("userId") Long userId,
                                                                  @Param("afterMessageId") Long afterMessageId,
                                                                  @Param("limit") int limit);

    @Select("SELECT m.id FROM consultation_message m "
            + "JOIN consultation_session s ON s.id = m.session_id "
            + "WHERE m.session_id = #{sessionId} AND s.user_id = #{userId} "
            + "ORDER BY m.id DESC LIMIT 1 OFFSET #{recentMessageCount}")
    Long selectSummaryTargetBeforeRecentMessages(@Param("sessionId") Long sessionId,
                                                @Param("userId") Long userId,
                                                @Param("recentMessageCount") int recentMessageCount);

    @Select("SELECT * FROM consultation_message WHERE session_id = #{sessionId} "
            + "AND id > #{afterMessageId} AND id <= #{throughMessageId} "
            + "ORDER BY id ASC LIMIT #{limit}")
    List<ConsultationMessage> selectWindowAfterWatermark(@Param("sessionId") Long sessionId,
                                                          @Param("afterMessageId") Long afterMessageId,
                                                          @Param("throughMessageId") Long throughMessageId,
                                                          @Param("limit") int limit);
}
