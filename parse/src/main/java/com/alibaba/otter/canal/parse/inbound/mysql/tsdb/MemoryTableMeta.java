package com.alibaba.otter.canal.parse.inbound.mysql.tsdb;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.alibaba.druid.sql.ast.SQLDataType;
import com.alibaba.druid.sql.ast.SQLDataTypeImpl;
import com.alibaba.druid.sql.ast.SQLExpr;
import com.alibaba.druid.sql.ast.SQLStatement;
import com.alibaba.druid.sql.ast.expr.SQLCharExpr;
import com.alibaba.druid.sql.ast.expr.SQLIdentifierExpr;
import com.alibaba.druid.sql.ast.expr.SQLNullExpr;
import com.alibaba.druid.sql.ast.expr.SQLPropertyExpr;
import com.alibaba.druid.sql.ast.statement.SQLColumnConstraint;
import com.alibaba.druid.sql.ast.statement.SQLColumnDefinition;
import com.alibaba.druid.sql.ast.statement.SQLColumnPrimaryKey;
import com.alibaba.druid.sql.ast.statement.SQLCreateTableStatement;
import com.alibaba.druid.sql.ast.statement.SQLNotNullConstraint;
import com.alibaba.druid.sql.ast.statement.SQLNullConstraint;
import com.alibaba.druid.sql.ast.statement.SQLSelectOrderByItem;
import com.alibaba.druid.sql.ast.statement.SQLTableElement;
import com.alibaba.druid.sql.dialect.mysql.ast.MySqlPrimaryKey;
import com.alibaba.druid.sql.repository.Schema;
import com.alibaba.druid.sql.repository.SchemaObject;
import com.alibaba.druid.sql.repository.SchemaRepository;
import com.alibaba.druid.util.JdbcConstants;
import com.alibaba.otter.canal.parse.inbound.TableMeta;
import com.alibaba.otter.canal.parse.inbound.TableMeta.FieldMeta;
import com.alibaba.otter.canal.parse.inbound.mysql.ddl.DruidDdlParser;
import com.alibaba.otter.canal.protocol.position.EntryPosition;

/**
 * 基于DDL维护的内存表结构
 *
 * @author agapple 2017年7月27日 下午4:19:40
 * @since 3.2.5
 */
public class MemoryTableMeta implements TableMetaTSDB {

    private Logger                       logger     = LoggerFactory.getLogger(MemoryTableMeta.class);
    private Map<List<String>, TableMeta> tableMetas = new ConcurrentHashMap<List<String>, TableMeta>();
    private SchemaRepository             repository = new SchemaRepository(JdbcConstants.MYSQL);

    public MemoryTableMeta(){
    }

    @Override
    public boolean init(String destination) {
        return true;
    }

    public boolean apply(EntryPosition position, String schema, String ddl, String extra) {
        tableMetas.clear();
        synchronized (this) {
            if (StringUtils.isNotEmpty(schema)) {
                repository.setDefaultSchema(schema);
            }

            try {
                repository.console(ddl);
            } catch (Throwable e) {
                logger.warn("parse faield : " + ddl, e);
            }
        }

        // TableMeta meta = find("tddl5_00", "ab");
        // if (meta != null) {
        // repository.setDefaultSchema("tddl5_00");
        // System.out.println(repository.console("show create table tddl5_00.ab"));
        // System.out.println(repository.console("show columns from tddl5_00.ab"));
        // }
        return true;
    }

    @Override
    public TableMeta find(String schema, String table) {
        List<String> keys = Arrays.asList(schema, table);
        TableMeta tableMeta = tableMetas.get(keys);
        if (tableMeta == null) {
            synchronized (this) {
                tableMeta = tableMetas.get(keys);
                if (tableMeta == null) {
                    Schema schemaRep = repository.findSchema(schema);
                    if (schema == null) {
                        return null;
                    }
                    SchemaObject data = schemaRep.findTable(table);
                    if (data == null) {
                        return null;
                    }
                    SQLStatement statement = data.getStatement();
                    if (statement == null) {
                        return null;
                    }
                    if (statement instanceof SQLCreateTableStatement) {
                        tableMeta = parse((SQLCreateTableStatement) statement);
                    }
                    if (tableMeta != null) {
                        if (table != null) {
                            tableMeta.setTable(table);
                        }
                        if (schema != null) {
                            tableMeta.setSchema(schema);
                        }

                        tableMetas.put(keys, tableMeta);
                    }
                }
            }
        }

        return tableMeta;
    }

    @Override
    public boolean rollback(EntryPosition position) {
        throw new RuntimeException("not support for memory");
    }

    public Map<String, String> snapshot() {
        Map<String, String> schemaDdls = new HashMap<String, String>();
        for (Schema schema : repository.getSchemas()) {
            StringBuffer data = new StringBuffer(4 * 1024);
            for (String table : schema.showTables()) {
                SchemaObject schemaObject = schema.findTable(table);
                schemaObject.getStatement().output(data);
                data.append("; \n");
            }
            schemaDdls.put(schema.getName(), data.toString());
        }

        return schemaDdls;
    }

    private TableMeta parse(SQLCreateTableStatement statement) {
        int size = statement.getTableElementList().size();
        if (size > 0) {
            TableMeta tableMeta = new TableMeta();
            for (int i = 0; i < size; ++i) {
                SQLTableElement element = statement.getTableElementList().get(i);
                processTableElement(element, tableMeta);
            }
            return tableMeta;
        }

        return null;
    }

    private void processTableElement(SQLTableElement element, TableMeta tableMeta) {
        if (element instanceof SQLColumnDefinition) {
            FieldMeta fieldMeta = new FieldMeta();
            SQLColumnDefinition column = (SQLColumnDefinition) element;
            String name = getSqlName(column.getName());
            // String charset = getSqlName(column.getCharsetExpr());
            SQLDataType dataType = column.getDataType();
            String dataTypStr = dataType.getName();
            if (dataType.getArguments().size() > 0) {
                dataTypStr += "(";
                for (int i = 0; i < column.getDataType().getArguments().size(); i++) {
                    if (i != 0) {
                        dataTypStr += ",";
                    }
                    SQLExpr arg = column.getDataType().getArguments().get(i);
                    dataTypStr += arg.toString();
                }
                dataTypStr += ")";
            }

            if (dataType instanceof SQLDataTypeImpl) {
                SQLDataTypeImpl dataTypeImpl = (SQLDataTypeImpl) dataType;
                if (dataTypeImpl.isUnsigned()) {
                    dataTypStr += " unsigned";
                }

                if (dataTypeImpl.isZerofill()) {
                    dataTypStr += " zerofill";
                }
            }

            if (column.getDefaultExpr() == null || column.getDefaultExpr() instanceof SQLNullExpr) {
                fieldMeta.setDefaultValue(null);
            } else {
                fieldMeta.setDefaultValue(DruidDdlParser.unescapeQuotaName(getSqlName(column.getDefaultExpr())));
            }

            fieldMeta.setColumnName(name);
            fieldMeta.setColumnType(dataTypStr);
            fieldMeta.setNullable(true);
            List<SQLColumnConstraint> constraints = column.getConstraints();
            for (SQLColumnConstraint constraint : constraints) {
                if (constraint instanceof SQLNotNullConstraint) {
                    fieldMeta.setNullable(false);
                } else if (constraint instanceof SQLNullConstraint) {
                    fieldMeta.setNullable(true);
                } else if (constraint instanceof SQLColumnPrimaryKey) {
                    fieldMeta.setKey(true);
                }
            }
            tableMeta.addFieldMeta(fieldMeta);
        } else if (element instanceof MySqlPrimaryKey) {
            MySqlPrimaryKey column = (MySqlPrimaryKey) element;
            List<SQLSelectOrderByItem> pks = column.getColumns();
            for (SQLSelectOrderByItem pk : pks) {
                String name = getSqlName(pk.getExpr());
                FieldMeta field = tableMeta.getFieldMetaByName(name);
                field.setKey(true);
            }
        }
    }

    private String getSqlName(SQLExpr sqlName) {
        if (sqlName == null) {
            return null;
        }

        if (sqlName instanceof SQLPropertyExpr) {
            SQLIdentifierExpr owner = (SQLIdentifierExpr) ((SQLPropertyExpr) sqlName).getOwner();
            return DruidDdlParser.unescapeName(owner.getName()) + "."
                   + DruidDdlParser.unescapeName(((SQLPropertyExpr) sqlName).getName());
        } else if (sqlName instanceof SQLIdentifierExpr) {
            return DruidDdlParser.unescapeName(((SQLIdentifierExpr) sqlName).getName());
        } else if (sqlName instanceof SQLCharExpr) {
            return ((SQLCharExpr) sqlName).getText();
        } else {
            return sqlName.toString();
        }
    }
    

    public SchemaRepository getRepository() {
        return repository;
    }

}
