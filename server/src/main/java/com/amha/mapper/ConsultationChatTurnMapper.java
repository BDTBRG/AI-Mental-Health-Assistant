package com.amha.mapper;

import com.amha.entity.ConsultationChatTurn;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface ConsultationChatTurnMapper extends BaseMapper<ConsultationChatTurn> {

    @Select("SELECT * FROM consultation_chat_turn WHERE session_id = #{sessionId} "
            + "AND client_request_id = #{requestId} LIMIT 1 FOR UPDATE")
    ConsultationChatTurn selectBySessionAndRequestForUpdate(@Param("sessionId") Long sessionId,
                                                             @Param("requestId") String requestId);

    @Select("SELECT * FROM consultation_chat_turn WHERE session_id = #{sessionId} "
            + "AND status = 'RUNNING' ORDER BY id LIMIT 1 FOR UPDATE")
    ConsultationChatTurn selectRunningBySessionForUpdate(@Param("sessionId") Long sessionId);
}
