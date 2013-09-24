package liquibase.snapshot;

import liquibase.database.Database;
import liquibase.database.core.*;
import liquibase.database.jvm.JdbcConnection;
import liquibase.exception.DatabaseException;
import liquibase.structure.DatabaseObject;
import liquibase.structure.core.Catalog;
import liquibase.structure.core.Index;
import liquibase.structure.core.Schema;
import liquibase.structure.core.Table;
import liquibase.util.StringUtils;

import java.sql.*;
import java.util.*;

public class JdbcDatabaseSnapshot extends DatabaseSnapshot {
    private CachingDatabaseMetaData cachingDatabaseMetaData;

    public JdbcDatabaseSnapshot(DatabaseObject[] examples, Database database, SnapshotControl snapshotControl) throws DatabaseException, InvalidExampleException {
        super(examples, database, snapshotControl);
    }

    public JdbcDatabaseSnapshot(DatabaseObject[] examples, Database database) throws DatabaseException, InvalidExampleException {
        super(examples, database);
    }

    public CachingDatabaseMetaData getMetaData() throws SQLException {
        if (cachingDatabaseMetaData == null) {
            DatabaseMetaData databaseMetaData = null;
            if (getDatabase().getConnection() != null) {
                databaseMetaData = ((JdbcConnection) getDatabase().getConnection()).getUnderlyingConnection().getMetaData();
            }

            cachingDatabaseMetaData = new CachingDatabaseMetaData(this.getDatabase(), databaseMetaData);
        }
        return cachingDatabaseMetaData;
    }

    public class CachingDatabaseMetaData {
        private DatabaseMetaData databaseMetaData;
        private Database database;

        public CachingDatabaseMetaData(Database database, DatabaseMetaData metaData) {
            this.databaseMetaData = metaData;
            this.database = database;
        }

        public DatabaseMetaData getDatabaseMetaData() {
            return databaseMetaData;
        }

        public List<CachedRow> getForeignKeys(final String catalogName, final String schemaName, final String tableName, final String fkName) throws DatabaseException {
            return getResultSetCache("getImportedKeys").get(new ResultSetCache.UnionResultSetExtractor(database) {

                @Override
                public ResultSetCache.RowData rowKeyParameters(CachedRow row) {
                    return new ResultSetCache.RowData(row.getString("FKTABLE_CAT"), row.getString("FKTABLE_SCHEM"), database, row.getString("FKTABLE_NAME"), row.getString("FK_NAME"));
                }

                @Override
                public ResultSetCache.RowData wantedKeyParameters() {
                    return new ResultSetCache.RowData(catalogName, schemaName, database, tableName, fkName);
                }

                @Override
                public List<CachedRow> fastFetch() throws SQLException, DatabaseException {
                    List<CachedRow> returnList = new ArrayList<CachedRow>();

                    List<String> tables = new ArrayList<String>();
                    if (tableName == null) {
                        for (CachedRow row : getTables(catalogName, schemaName, null, new String[] {"TABLE"})) {
                            tables.add(row.getString("TABLE_NAME"));
                        }
                    } else {
                        tables.add(tableName);
                    }


                    for (String foundTable : tables) {
                        returnList.addAll(extract(databaseMetaData.getImportedKeys(catalogName, schemaName, foundTable)));
                    }

                    return returnList;
                }

                @Override
                public List<CachedRow> bulkFetch() throws SQLException, DatabaseException {
                    return null;
                }

                @Override
                boolean shouldBulkSelect(ResultSetCache resultSetCache) {
                    return false;
                }
            });
        }

        public List<CachedRow> getIndexInfo(final String catalogName, final String schemaName, final String tableName, final String indexName) throws DatabaseException {
            return getResultSetCache("getIndexInfo").get(new ResultSetCache.UnionResultSetExtractor(database) {

                @Override
                public ResultSetCache.RowData rowKeyParameters(CachedRow row) {
                    return new ResultSetCache.RowData(row.getString("TABLE_CAT"), row.getString("TABLE_SCHEM"), database, row.getString("TABLE_NAME"), row.getString("INDEX_NAME"));
                }

                @Override
                public ResultSetCache.RowData wantedKeyParameters() {
                    return new ResultSetCache.RowData(catalogName, schemaName, database, tableName, indexName);
                }

                @Override
                public List<CachedRow> fastFetch() throws SQLException, DatabaseException {
                    List<CachedRow> returnList = new ArrayList<CachedRow>();

                    if (database instanceof OracleDatabase) {
                        //oracle getIndexInfo is buggy and slow.  See Issue 1824548 and http://forums.oracle.com/forums/thread.jspa?messageID=578383&#578383
                        String sql = "SELECT INDEX_NAME, 3 AS TYPE, TABLE_NAME, COLUMN_NAME, COLUMN_POSITION AS ORDINAL_POSITION, null AS FILTER_CONDITION FROM ALL_IND_COLUMNS " +
                                "WHERE TABLE_OWNER='" + schemaName + "'";
                        if (tableName != null) {
                            sql += " AND TABLE_NAME='" + database.correctObjectName(tableName, Table.class) + "'";
                        }

                        if (indexName != null) {
                            sql += " AND INDEX_NAME='" + database.correctObjectName(indexName, Index.class) + "'";
                        }

                        sql += " ORDER BY INDEX_NAME, ORDINAL_POSITION";

                        returnList.addAll(extract(executeQuery(sql, database)));
                    } else {
                        List<String> tables = new ArrayList<String>();
                        if (tableName == null) {
                            for (CachedRow row : getTables(catalogName, schemaName, null, new String[] {"TABLE"})) {
                                tables.add(row.getString("TABLE_NAME"));
                            }
                        } else {
                            tables.add(tableName);
                        }


                        for (String tableName : tables) {
                            returnList.addAll(extract(databaseMetaData.getIndexInfo(catalogName, schemaName, tableName, false, true)));
                        }
                    }

                    return returnList;
                }

                @Override
                public List<CachedRow> bulkFetch() throws SQLException, DatabaseException {
                    return null;
                }

                @Override
                boolean shouldBulkSelect(ResultSetCache resultSetCache) {
                    return false;
                }
            });
        }

        /**
         * Return the columns for the given catalog, schema, table, and column.
         */
        public List<CachedRow> getColumns(final String catalogName, final String schemaName, final String tableName, final String columnName) throws SQLException, DatabaseException {
            return getResultSetCache("getColumns").get(new ResultSetCache.SingleResultSetExtractor(database) {

                @Override
                public ResultSetCache.RowData rowKeyParameters(CachedRow row) {
                    return new ResultSetCache.RowData(row.getString("TABLE_CAT"), row.getString("TABLE_SCHEM"), database, row.getString("TABLE_NAME"), row.getString("COLUMN_NAME"));
                }

                @Override
                public ResultSetCache.RowData wantedKeyParameters() {
                    return new ResultSetCache.RowData(catalogName, schemaName, database, tableName, columnName);
                }

                @Override
                boolean shouldBulkSelect(ResultSetCache resultSetCache) {
                    Set<String> seenTables = resultSetCache.getInfo("seenTables", Set.class);
                    if (seenTables == null) {
                        seenTables = new HashSet<String>();
                        resultSetCache.putInfo("seenTables", seenTables);
                    }

                    seenTables.add(catalogName+":"+schemaName+":"+tableName);
                    return seenTables.size() > 2;
                }

                @Override
                public ResultSet fastFetchQuery() throws SQLException {
                    return databaseMetaData.getColumns(catalogName, schemaName, tableName, columnName);
                }

                @Override
                public ResultSet bulkFetchQuery() throws SQLException {
                    return databaseMetaData.getColumns(catalogName, schemaName, null, null);
                }
            });
        }

        public List<CachedRow> getTables(final String catalogName, final String schemaName, final String table, final String[] types) throws SQLException, DatabaseException {
            return getResultSetCache("getTables."+StringUtils.join(types, ":")).get(new ResultSetCache.SingleResultSetExtractor(database) {

                @Override
                public ResultSetCache.RowData rowKeyParameters(CachedRow row) {
                    return new ResultSetCache.RowData(row.getString("TABLE_CAT"), row.getString("TABLE_SCHEM"), database, row.getString("TABLE_NAME"));
                }

                @Override
                public ResultSetCache.RowData wantedKeyParameters() {
                    return new ResultSetCache.RowData(catalogName, schemaName, database, table);
                }

                @Override
                public ResultSet fastFetchQuery() throws SQLException {
                    return databaseMetaData.getTables(catalogName, schemaName, database.correctObjectName(table, Table.class), types);
                }

                @Override
                public ResultSet bulkFetchQuery() throws SQLException {
                    return databaseMetaData.getTables(catalogName, schemaName, null, types);
                }
            });
        }

        public List<CachedRow> getPrimaryKeys(final String catalogName, final String schemaName, final String table) throws SQLException, DatabaseException {
            return getResultSetCache("getPrimaryKeys").get(new ResultSetCache.SingleResultSetExtractor(database) {

                @Override
                public ResultSetCache.RowData rowKeyParameters(CachedRow row) {
                    return new ResultSetCache.RowData(row.getString("TABLE_CAT"), row.getString("TABLE_SCHEM"),  database, row.getString("TABLE_NAME"));
                }

                @Override
                public ResultSetCache.RowData wantedKeyParameters() {
                    return new ResultSetCache.RowData(catalogName, schemaName, database, table);
                }

                @Override
                public ResultSet fastFetchQuery() throws SQLException {
                    return databaseMetaData.getPrimaryKeys(catalogName, schemaName, table);
                }

                @Override
                public ResultSet bulkFetchQuery() throws SQLException {
                    return null;
                }

                @Override
                boolean shouldBulkSelect(ResultSetCache resultSetCache) {
                    return false;
                }
            });
        }

        public List<CachedRow> getUniqueConstraints(final String catalogName, final String schemaName, final String tableName) throws SQLException, DatabaseException {
            return getResultSetCache("getUniqueConstraints").get(new ResultSetCache.SingleResultSetExtractor(database) {

                @Override
                public ResultSetCache.RowData rowKeyParameters(CachedRow row) {
                    return new ResultSetCache.RowData(row.getString("TABLE_CAT"), row.getString("TABLE_SCHEM"), database, row.getString("TABLE_NAME"));
                }

                @Override
                public ResultSetCache.RowData wantedKeyParameters() {
                    return new ResultSetCache.RowData(catalogName, schemaName, database, tableName);
                }

                @Override
                public ResultSet fastFetchQuery() throws SQLException, DatabaseException {
                    return executeQuery(createSql(catalogName, schemaName, tableName), JdbcDatabaseSnapshot.this.getDatabase());
                }

                @Override
                public ResultSet bulkFetchQuery() throws SQLException, DatabaseException {
                    return executeQuery(createSql(catalogName, schemaName, null), JdbcDatabaseSnapshot.this.getDatabase());
                }

                private String createSql(String catalogName, String schemaName, String tableName) throws SQLException {
                    Database database = JdbcDatabaseSnapshot.this.getDatabase();
                    String sql;
                    if (database instanceof MySQLDatabase || database instanceof HsqlDatabase) {
                        sql = "select CONSTRAINT_NAME " +
                                "from information_schema.table_constraints " +
                                "where constraint_schema='" + database.correctObjectName(catalogName, Catalog.class) + "' " +
                                "and constraint_type='UNIQUE'";
                        if (tableName != null) {
                            sql += " and table_name='" + database.correctObjectName(tableName, Table.class) + "'";
                        }
                    } else if (database instanceof PostgresDatabase) {
                        sql = "select CONSTRAINT_NAME " +
                                "from information_schema.table_constraints " +
                                "where constraint_catalog='" + database.correctObjectName(catalogName, Catalog.class) + "' " +
                                "and constraint_schema='"+database.correctObjectName(schemaName, Schema.class)+"' " +
                                "and constraint_type='UNIQUE'";
                        if (tableName != null) {
                                sql += " and table_name='" + database.correctObjectName(tableName, Table.class) + "'";
                        }
                    } else if (database instanceof MSSQLDatabase) {
                        sql = "select Constraint_Name from information_schema.table_constraints " +
                                "where constraint_type = 'Unique' " +
                                "and constraint_schema='"+database.correctObjectName(schemaName, Schema.class)+"'";
                        if (tableName != null) {
                                sql += " and table_name='"+database.correctObjectName(tableName, Table.class)+"'";
                        }
                    } else if (database instanceof OracleDatabase) {
                        sql = "select uc.constraint_name, uc.table_name,uc.status,uc.deferrable,uc.deferred,ui.tablespace_name from all_constraints uc, all_cons_columns ucc, all_indexes ui " +
                                "where uc.constraint_type='U' and uc.index_name = ui.index_name and uc.constraint_name = ucc.constraint_name " +
                                "and uc.owner = '" + database.correctObjectName(catalogName, Catalog.class) + "' " +
                                "and ui.table_owner = '" + database.correctObjectName(catalogName, Catalog.class) + "' " +
                                "and ucc.owner = '" + database.correctObjectName(catalogName, Catalog.class) + "'";
                        if (tableName != null) {
                            sql += " and uc.table_name = '" + database.correctObjectName(tableName, Table.class) + "'";
                        }
                    } else if (database instanceof DB2Database) {
                        sql = "select distinct k.constname as constraint_name from syscat.keycoluse k, syscat.tabconst t " +
                                "where k.constname = t.constname " +
                                "and t.tabschema = '" + database.correctObjectName(catalogName, Catalog.class) + "' " +
                                "and t.type='U'";
                        if (tableName != null) {
                            sql += " and t.tabname = '" + database.correctObjectName(tableName, Table.class) + "'";
                        }
                    } else if (database instanceof FirebirdDatabase) {
                        sql = "SELECT RDB$INDICES.RDB$INDEX_NAME AS CONSTRAINT_NAME FROM RDB$INDICES " +
                                "LEFT JOIN RDB$RELATION_CONSTRAINTS ON RDB$RELATION_CONSTRAINTS.RDB$INDEX_NAME = RDB$INDICES.RDB$INDEX_NAME " +
                                "WHERE RDB$INDICES.RDB$UNIQUE_FLAG IS NOT NULL " +
                                "AND RDB$RELATION_CONSTRAINTS.RDB$CONSTRAINT_TYPE != 'PRIMARY KEY' "+
                                "AND NOT(RDB$INDICES.RDB$INDEX_NAME LIKE 'RDB$%')";
                        if (tableName != null) {
                            sql += " AND RDB$INDICES.RDB$RELATION_NAME='"+database.correctObjectName(tableName, Table.class)+"'";
                        }
                    } else if (database instanceof DerbyDatabase) {
                        sql = "select c.constraintname as CONSTRAINT_NAME " +
                                "from sys.systables t, sys.sysconstraints c, sys.sysschemas s " +
                                "where s.schemaname='"+database.correctObjectName(catalogName, Catalog.class)+"' "+
                                "and t.tableid = c.tableid " +
                                "and t.schemaid=s.schemaid " +
                                "and c.type = 'U'";
                        if (tableName != null) {
                            sql += " AND t.tablename = '"+database.correctObjectName(tableName, Table.class)+"'";
                        }
                    } else {
                        sql = "select CONSTRAINT_NAME, CONSTRAINT_TYPE " +
                                "from information_schema.constraints " +
                                "where constraint_schema='" + database.correctObjectName(schemaName, Schema.class) + "' " +
                                "and constraint_catalog='" + database.correctObjectName(catalogName, Catalog.class) + "' " +
                                "and constraint_type='UNIQUE'";
                        if (tableName != null) {
                                sql += " and table_name='" + database.correctObjectName(tableName, Table.class) + "'";
                        }

                    }

                    return sql;
                }
            });
        }
    }

}
