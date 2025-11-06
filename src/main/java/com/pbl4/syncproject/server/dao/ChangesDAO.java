package com.pbl4.syncproject.server.dao;

import com.google.gson.JsonObject;
import java.sql.*;
import java.util.*;

public class ChangesDAO {
    public static long insertFileChange(Connection c, int fileId, String changeType,
                                        int version, String hash, Long size,
                                        Integer folderId, String fileName, Integer userId) throws SQLException {
        String sql = """
          INSERT INTO Changes(EntityType,EntityId,ChangeType,Version,FileHash,Size,FolderId,FileName,UserId)
          VALUES('FILE',?,?,?,?,?,?,?,?)
        """;
        try (PreparedStatement ps = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setInt(1, fileId);
            ps.setString(2, changeType);
            ps.setInt(3, version);
            ps.setString(4, hash);
            if (size == null) ps.setNull(5, Types.BIGINT); else ps.setLong(5, size);
            if (folderId == null) ps.setNull(6, Types.INTEGER); else ps.setInt(6, folderId);
            ps.setString(7, fileName);
            if (userId == null) ps.setNull(8, Types.INTEGER); else ps.setInt(8, userId);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        }
    }

    public static long getLastSeq(Connection c) throws SQLException {
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT COALESCE(MAX(Seq),0) AS s FROM Changes")) {
            return rs.next() ? rs.getLong("s") : 0L;
        }
    }

    public static List<JsonObject> getSince(Connection c, long sinceSeq) throws SQLException {
        String sql = "SELECT * FROM Changes WHERE Seq > ? ORDER BY Seq ASC LIMIT 1000";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, sinceSeq);
            ResultSet rs = ps.executeQuery();
            List<JsonObject> out = new ArrayList<>();
            while (rs.next()) {
                JsonObject o = new JsonObject();
                o.addProperty("Seq", rs.getLong("Seq"));
                o.addProperty("type", "FILE_" + rs.getString("ChangeType"));
                JsonObject d = new JsonObject();
                d.addProperty("FileID",    rs.getInt("EntityId"));
                d.addProperty("Version",   rs.getInt("Version"));
                if (rs.getString("FileHash") != null) d.addProperty("FileHash", rs.getString("FileHash"));
                if (rs.getObject("FolderId") != null) d.addProperty("FolderID", rs.getInt("FolderId"));
                if (rs.getString("FileName") != null) d.addProperty("FileName", rs.getString("FileName"));
                if (rs.getObject("Size") != null) d.addProperty("FileSize", rs.getLong("Size"));
                o.add("data", d);
                out.add(o);
            }
            return out;
        }
    }
}
