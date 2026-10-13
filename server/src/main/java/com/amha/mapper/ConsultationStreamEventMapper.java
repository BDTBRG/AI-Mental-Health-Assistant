package com.amha.mapper;

import com.amha.entity.ConsultationStreamEvent;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface ConsultationStreamEventMapper extends BaseMapper<ConsultationStreamEvent> {

    @Select("SELECT * FROM consultation_stream_event "
            + "WHERE session_id = #{sessionId} AND client_request_id = #{clientRequestId} "
            + "AND id > #{afterEventId} ORDER BY id ASC LIMIT 200")
    List<ConsultationStreamEvent> selectAfter(@Param("sessionId") Long sessionId,
                                               @Param("clientRequestId") String clientRequestId,
                                               @Param("afterEventId") Long afterEventId);

    @Select("SELECT MAX(sequence) FROM consultation_stream_event "
            + "WHERE session_id = #{sessionId} AND client_request_id = #{clientRequestId}")
    Integer selectMaxSequence(@Param("sessionId") Long sessionId,
                              @Param("clientRequestId") String clientRequestId);
}
