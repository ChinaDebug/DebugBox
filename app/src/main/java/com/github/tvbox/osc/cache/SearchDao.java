package com.github.tvbox.osc.cache;

import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.Query;

import org.jspecify.annotations.NonNull;

import java.util.List;

@Dao
public interface SearchDao {

    @Insert
    void insert(SearchHistory trackData);

    @Delete
    void delete(SearchHistory trackData);

    @Query("DELETE FROM T_SEARCH")
    void deleteAll();

    @Query("SELECT * FROM T_SEARCH")
    @NonNull
    List<@NonNull SearchHistory> getAll();

    @Query("SELECT * FROM T_SEARCH WHERE searchKeyWords=:keyword")
    @NonNull
    List<@NonNull SearchHistory> getByKeywords(String keyword);
}
