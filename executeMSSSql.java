package execute_mssql.aia.tss.tool;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.sql.Statement;
import java.util.Collection;
import java.util.Properties;

import org.apache.commons.io.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ExecuteMSSQL {

    private static final Properties PROP = initPROP();

    public static final String UTF8_BOM = "\uFEFF";// UTF-8 bom encode

    private static Logger logger = LoggerFactory.getLogger(ExecuteMSSQL.class);

    private static final Connection conn = getConnection();

    public static Properties initPROP() {
	Properties prop = new Properties();
	try (FileInputStream inputStream = new FileInputStream(
		new File(System.getProperty("user.dir") + "/config.properties"))) {
	    prop.load(inputStream);
	} catch (FileNotFoundException e) {
	    logger.error(e.getMessage());
	} catch (IOException e) {
	    logger.error(e.getMessage());
	}
	return prop;
    }

    public static Collection<File> getSqlFileList(String dir) {
	return FileUtils.listFiles(new File(PROP.getProperty(dir)), new String[] { "sql" }, true);
    }

    public static Connection getConnection() {
	Connection conn = null;
	try {
	    Class.forName("com.microsoft.sqlserver.jdbc.SQLServerDriver");
	    conn = DriverManager.getConnection(PROP.getProperty("sqlurl"), PROP.getProperty("sqluser"),
		    PROP.getProperty("sqlpwd"));
	} catch (Exception e) {
	    logger.error(e.getMessage());
	}
	return conn;
    }

    public static void prettyPrintResultSet(ResultSet rs) throws SQLException {
	ResultSetMetaData rsmd = rs.getMetaData();
	StringBuffer sb = new StringBuffer();
	sb.append("\n");
	if (rsmd.getColumnCount() != 0) {
	    for (int k = 1; k <= rsmd.getColumnCount(); k++) {
		sb.append(rsmd.getColumnName(k)).append("\t");
	    }
	    sb.append("\n");
	    for (int k = 1; k <= rsmd.getColumnCount(); k++) {
		for (int j = 1; j <= rsmd.getColumnName(k).length(); j++) {
		    sb.append("-");
		}
		sb.append("\t");
	    }
	    sb.append("\n");
	}

	while (rs.next()) {
	    for (int j = 1; j <= rsmd.getColumnCount(); j++) {
		String filedContent = rs.getObject(j) != null ? rs.getObject(j).toString() : "null";
		StringBuffer content = new StringBuffer(filedContent);
		if (rsmd.getColumnName(j).length() < filedContent.length()) {
		    if (rsmd.getColumnName(j).length() > 3) {
			filedContent = filedContent.substring(0, rsmd.getColumnName(j).length() - 3) + "...";
		    }
		} else {
		    for (int m = filedContent.length(); m <= rsmd.getColumnName(j).length(); m++) {
			content.append(" ");
		    }
		    filedContent = content.toString();
		}
		sb.append(filedContent).append("\t");
	    }
	    sb.append("\n");
	}
	logger.info(sb.toString());
    }

//    public static void executeSql(String sql, Connection conn) throws SQLException {
//	logger.info("execute sql: {}", sql);
//	try (Statement statement = conn.createStatement()) {
//	    if (statement.execute(sql)) {
//		ResultSet rs = statement.getResultSet();
//		prettyPrintResultSet(rs);
//	    } else {
//		int affectedRow = statement.getUpdateCount();
//		if (affectedRow > 0) {
//		    logger.info(affectedRow + " affected");
//		}
//	    }
//	}
//    }

    public static void executeSingleSql(String sql) throws SQLException {
	try {
	    conn.setAutoCommit(false);
	    String[] stmts = sql.split("[\n\r][ \t]*[Gg][Oo][ \t]*(?!.)");
	    for (String stmtSql : stmts) {
		try (Statement stmt = conn.createStatement()) {
		    boolean hasResultSet = stmt.execute(stmtSql);
		    int updatedCount = hasResultSet ? -1 : stmt.getUpdateCount();
		    SQLWarning warning = stmt.getWarnings();
		    while (warning != null) {
			logger.info("Message: {}", warning.getMessage());
			warning = warning.getNextWarning();
		    }
		    do {
			if (hasResultSet) {
			    prettyPrintResultSet(stmt.getResultSet());
			} else {
			    logger.info("({} row(s) affected)", updatedCount);
			}
			hasResultSet = stmt.getMoreResults();
			updatedCount = stmt.getUpdateCount();
		    } while (hasResultSet || updatedCount != -1);
		}
	    }
	    if (PROP.getProperty("commit_tran").equalsIgnoreCase("true")) {
		conn.commit();
	    } else {
		conn.rollback();
	    }
	    conn.setAutoCommit(true);
	} catch (Exception e) {
	    logger.error("Error sql: {}", sql);
	    try {
		conn.rollback();
		logger.info("Execute SQL error: DB name: {}, message: {}", conn.getCatalog(), e.getMessage());
	    } catch (SQLException e1) {
		logger.error("Error in rollback.", e1.getMessage());
	    }
	    throw new SQLException(e);
	}
    }

    public static void close() {
	if (conn != null) {
	    try {
		conn.close();
	    } catch (SQLException e) {
		logger.error(e.getMessage());
	    }
	}
    }

    public static String readSqlFile(File file) throws IOException {
	String sqlContent = FileUtils.readFileToString(file);
	if (sqlContent.startsWith("��")) {
	    return FileUtils.readFileToString(file, "Unicode");
	} else if (sqlContent.startsWith(UTF8_BOM)) {
	    return FileUtils.readFileToString(file, "UTF-8").substring(1);
	}
	return sqlContent;
    }

    public static void executeBatchSqlFromDir(String beOrfeDir) {
	Collection<File> fileList = getSqlFileList(beOrfeDir);
	if (fileList.size() > Integer.valueOf(PROP.getProperty("MAX_Scripts_Count"))) {
	    logger.info("scripts count exceed the MAX_Scripts_Count");
	    return;
	}
	String preSql = null;
	if (beOrfeDir.toLowerCase().contains("fe")) {
	    preSql = PROP.getProperty("sqlPreSetting").replace("{DB}", PROP.getProperty("FEDB"));
	} else {
	    preSql = PROP.getProperty("sqlPreSetting").replace("{DB}", PROP.getProperty("BEDB"));
	}
	try {
	    executeSingleSql(preSql);
	} catch (SQLException e1) {
	    logger.error(preSql + " executed Failed!");
	    logger.error(e1.getMessage());
	    return;
	}
	for (File sql : getSqlFileList(beOrfeDir)) {
	    logger.info("exec " + sql.getAbsolutePath());
	    try {
		executeSingleSql(readSqlFile(sql));
	    } catch (Exception e) {
		logger.error(sql.getName() + " executed Failed!");
		logger.error(e.getMessage());
	    }

	}

    }

    public static void main(String[] args) {
	try {
	    executeBatchSqlFromDir("BEsqlFilePath");
	    executeBatchSqlFromDir("FEsqlFilePath");
	} catch (Exception e) {
	    logger.error(e.getMessage());
	    throw new RuntimeException(e.getMessage(), e.getCause());
	} finally {
	    close();
	}
    }
}
