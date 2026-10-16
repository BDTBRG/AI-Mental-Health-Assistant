package com.amha.mapper;

import com.amha.entity.ConsultationChatTurn;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface ConsultationChatTurnMapper extends BaseMapper<ConsultationChatTurn> {

    @Select("SELECT * FROM consultation_chat_turn WHERE session_id = #{sessionId} "
            + "AND client_request_id = #{requestId} LIMIT 1")
    ConsultationChatTurn selectBySessionAndRequest(@Param("sessionId") Long sessionId,
                                                    @Param("requestId") String requestId);

    @Select("SELECT * FROM consultation_chat_turn WHERE session_id = #{sessionId} "
            + "AND client_request_id = #{requestId} LIMIT 1 FOR UPDATE")
    ConsultationChatTurn selectBySessionAndRequestForUpdate(@Param("sessionId") Long sessionId,
                                                             @Param("requestId") String requestId);

    @Select("SELECT * FROM consultation_chat_turn WHERE session_id = #{sessionId} "
            + "AND status = 'RUNNING' ORDER BY id LIMIT 1 FOR UPDATE")
    ConsultationChatTurn selectRunningBySessionForUpdate(@Param("sessionId") Long sessionId);

    @Select("SELECT * FROM consultation_chat_turn WHERE session_id = #{sessionId} "
            + "AND client_request_id <> #{requestId} "
            + "AND status IN ('RUNNING', 'PENDING', 'WAITING_CONTEXT') ORDER BY id FOR UPDATE")
    List<ConsultationChatTurn> selectOtherActiveBySessionForUpdate(@Param("sessionId") Long sessionId,
                                                                   @Param("requestId") String requestId);

    @Update("UPDATE consultation_chat_turn SET status = 'CONTEXT_SUPERSEDED', updated_at = CURRENT_TIMESTAMP "
            + "WHERE session_id = #{sessionId} AND client_request_id <> #{requestId} "
            + "AND status = 'CONTEXT_UNAVAILABLE'")
    int supersedeUnavailableBySessionExceptRequest(@Param("sessionId") Long sessionId,
                                                   @Param("requestId") String requestId);
}
