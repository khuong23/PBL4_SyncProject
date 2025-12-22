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

    public static long insertFolderChange(
            Connection c, int folderId, String changeType,
            String folderName, Integer parentId, Integer userId, int version
    ) throws SQLException {
        String sql = """
      INSERT INTO Changes(EntityType,EntityId,ChangeType,Version,FolderId,FileName,UserId)
      VALUES('FOLDER', ?, ?, ?, ?, ?, ?)
    """;
        try (PreparedStatement ps = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setInt(1, folderId);
            ps.setString(2, changeType);
            ps.setInt(3, version);
            if (parentId == null) ps.setNull(4, Types.INTEGER); else ps.setInt(4, parentId);
            ps.setString(5, folderName);
            if (userId == null) ps.setNull(6, Types.INTEGER); else ps.setInt(6, userId);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
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
                String et = rs.getString("EntityType");
                String ct = rs.getString("ChangeType");
                o.addProperty("type", et + "_" + ct);

                JsonObject d = new JsonObject();
                int entityId = rs.getInt("EntityId");
                d.addProperty("EntityId", entityId);

                if (rs.getString("FileName") != null) d.addProperty("Name", rs.getString("FileName"));
                if (rs.getObject("Version") != null) d.addProperty("Version", rs.getInt("Version"));
                if (rs.getString("FileHash") != null) d.addProperty("FileHash", rs.getString("FileHash"));
                if (rs.getObject("Size") != null) d.addProperty("FileSize", rs.getLong("Size"));

                // Map FolderId field based on entity type
                Integer folderIdDb = (rs.getObject("FolderId") != null) ? rs.getInt("FolderId") : null;
                if ("FILE".equals(et)) {
                    if (folderIdDb != null) d.addProperty("FolderID", folderIdDb);
                } else if ("FOLDER".equals(et)) {
                    d.addProperty("FolderID", entityId);
                    if (folderIdDb != null) d.addProperty("ParentFolderID", folderIdDb);
                }
                o.add("data", d);
                out.add(o);
            }
            return out;
        }
    }
}
