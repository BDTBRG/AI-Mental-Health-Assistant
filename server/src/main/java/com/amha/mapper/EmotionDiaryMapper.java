package com.amha.mapper;

import com.amha.entity.EmotionDiary;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface EmotionDiaryMapper extends BaseMapper<EmotionDiary> {

    @Select("SELECT * FROM emotion_diary WHERE id = #{id} FOR UPDATE")
    EmotionDiary selectByIdForUpdate(Long id);
}
