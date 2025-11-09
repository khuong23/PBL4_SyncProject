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
            ps.setInt(1, fileId);                 // EntityId
            ps.setString(2, changeType);          // ChangeType
            ps.setInt(3, version);                // Version
            ps.setString(4, hash);                // FileHash
            if (size == null) ps.setNull(5, Types.BIGINT); else ps.setLong(5, size); // Size
            if (folderId == null) ps.setNull(6, Types.INTEGER); else ps.setInt(6, folderId); // FolderId
            ps.setString(7, fileName);            // FileName
            if (userId == null) ps.setNull(8, Types.INTEGER); else ps.setInt(8, userId);     // UserId
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
            ps.setInt(1, folderId);                 // EntityId = chính folder
            ps.setString(2, changeType);            // CREATE/RENAME/MOVE/DELETE...
            ps.setInt(3, version);                  // Version của folder
            if (parentId == null) ps.setNull(4, Types.INTEGER); else ps.setInt(4, parentId); // FolderId = parent (container)
            ps.setString(5, folderName);            // tên thư mục (reuse FileName)
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
                o.addProperty("type", et + "_" + ct); // FILE_UPDATE / FOLDER_CREATE...

                JsonObject d = new JsonObject();
                int entityId = rs.getInt("EntityId");
                d.addProperty("EntityId", entityId);

                // chung cho cả FILE/FOLDER
                if (rs.getString("FileName") != null) d.addProperty("Name", rs.getString("FileName"));
                if (rs.getObject("Version") != null) d.addProperty("Version", rs.getInt("Version"));
                if (rs.getString("FileHash") != null) d.addProperty("FileHash", rs.getString("FileHash"));
                if (rs.getObject("Size") != null) d.addProperty("FileSize", rs.getLong("Size"));

                // container mapping theo entity type
                Integer folderIdDb = (rs.getObject("FolderId") != null) ? rs.getInt("FolderId") : null;
                if ("FILE".equals(et)) {
                    if (folderIdDb != null) d.addProperty("FolderID", folderIdDb);          // container của file
                } else if ("FOLDER".equals(et)) {
                    d.addProperty("FolderID", entityId);                                    // id của thư mục
                    if (folderIdDb != null) d.addProperty("ParentFolderID", folderIdDb);    // container (cha)
                }
                o.add("data", d);
                out.add(o);
            }
            return out;
        }
    }
}
