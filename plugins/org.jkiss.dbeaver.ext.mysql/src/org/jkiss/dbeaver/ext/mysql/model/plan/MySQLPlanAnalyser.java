/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2019 Serge Rider (serge@jkiss.org)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jkiss.dbeaver.ext.mysql.model.plan;

import org.jkiss.dbeaver.ext.mysql.model.MySQLDataSource;
import org.jkiss.dbeaver.model.exec.DBCException;
import org.jkiss.dbeaver.model.exec.DBCSession;
import org.jkiss.dbeaver.model.exec.jdbc.JDBCPreparedStatement;
import org.jkiss.dbeaver.model.exec.jdbc.JDBCResultSet;
import org.jkiss.dbeaver.model.exec.jdbc.JDBCSession;
import org.jkiss.dbeaver.model.impl.plan.AbstractExecutionPlan;
import org.jkiss.dbeaver.model.sql.SQLUtils;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * MySQL execution plan analyser
 */
public class MySQLPlanAnalyser extends AbstractExecutionPlan {

    private MySQLDataSource dataSource;
    private String query;
    private List<MySQLPlanNode> rootNodes;

    public MySQLPlanAnalyser(MySQLDataSource dataSource, String query)
    {
        this.dataSource = dataSource;
        this.query = query;
    }

    @Override
    public String getQueryString()
    {
        return query;
    }

    @Override
    public String getPlanQueryString() {
        return "EXPLAIN EXTENDED " + query;
    }

    @Override
    public List<MySQLPlanNode> getPlanNodes()
    {
        return rootNodes;
    }

    public void explain(DBCSession session)
        throws DBCException
    {
        String plainQuery = SQLUtils.stripComments(SQLUtils.getDialectFromObject(session.getDataSource()), query).toUpperCase();
        if (!plainQuery.startsWith("SELECT")) {
            throw new DBCException("Only SELECT statements could produce execution plan");
        }
        JDBCSession connection = (JDBCSession) session;
        try {
            try (JDBCPreparedStatement dbStat = connection.prepareStatement(getPlanQueryString())) {
                try (JDBCResultSet dbResult = dbStat.executeQuery()) {
                    List<MySQLPlanNode> nodes = new ArrayList<>();
                    while (dbResult.next()) {
                        MySQLPlanNode node = new MySQLPlanNode(null, dbResult);
                        nodes.add(node);
                    }

                    rootNodes = convertToPlanTree(nodes);
                }
            }
        } catch (SQLException e) {
            throw new DBCException(e, session.getDataSource());
        }
    }

    private List<MySQLPlanNode> convertToPlanTree(List<MySQLPlanNode> srcNodes) {
        List<MySQLPlanNode> roots = new ArrayList<>();

        if (srcNodes.size() == 1) {
            // Just one node
            roots.add(srcNodes.get(0));
        } else {
            List<MySQLPlanNode> parsed = new ArrayList<>();
            for (int id = 1; ; id++) {
                List<MySQLPlanNode> nodes = getQueriesById(srcNodes, id);
                if (nodes.isEmpty()) {
                    break;
                }
                if (nodes.size() == 1) {
                    roots.add(nodes.get(0));
                } else {
                    roots.add(joinNodes(srcNodes, nodes));
                }
                parsed.addAll(nodes);
            }
            // Add the rest
            for (MySQLPlanNode node : srcNodes) {
                if (!parsed.contains(node)) {
                    roots.add(node);
                }
            }
        }

        return roots;
    }

    private List<MySQLPlanNode> getQueriesById(List<MySQLPlanNode> srcNodes, int id) {
        List<MySQLPlanNode> subList = new ArrayList<>();
        for (MySQLPlanNode node : srcNodes) {
            if (node.getId() != null && node.getId() == id) {
                subList.add(node);
            }
        }
        return subList;
    }

    private MySQLPlanNode joinNodes(List<MySQLPlanNode> srcNodes, List<MySQLPlanNode> nodes) {
        MySQLPlanNode result = null;
        MySQLPlanNode leftNode = nodes.get(0);
        for (int i = 1 ; i < nodes.size(); i++) {
            leftNode = new MySQLPlanNodeJoin(leftNode.getParent(), leftNode, nodes.get(i));
            if (result == null) {
                result = leftNode;
            }
        }
        return result;
    }

}
