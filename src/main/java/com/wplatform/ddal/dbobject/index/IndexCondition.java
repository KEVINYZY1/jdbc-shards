/*
 * Copyright 2014-2015 the original author or authors
 *
 * Licensed under the Apache License, Version 2.0 (the “License”);
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an “AS IS” BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.wplatform.ddal.dbobject.index;

import com.wplatform.ddal.command.dml.Query;
import com.wplatform.ddal.command.expression.Comparison;
import com.wplatform.ddal.command.expression.Expression;
import com.wplatform.ddal.command.expression.ExpressionColumn;
import com.wplatform.ddal.command.expression.ExpressionVisitor;
import com.wplatform.ddal.dbobject.table.Column;
import com.wplatform.ddal.dbobject.table.Table;
import com.wplatform.ddal.engine.Session;
import com.wplatform.ddal.message.DbException;
import com.wplatform.ddal.result.ResultInterface;
import com.wplatform.ddal.util.StatementBuilder;
import com.wplatform.ddal.value.CompareMode;
import com.wplatform.ddal.value.Value;

import java.util.*;

/**
 * A index condition object is made for each condition that can potentially use
 * an index. This class does not extend expression, but in general there is one
 * expression that maps to each index condition.
 *
 * @author Thomas Mueller
 * @author Noel Grandin
 * @author Nicolas Fortin, Atelier SIG, IRSTV FR CNRS 24888
 */
public class IndexCondition {

    /**
     * A bit of a search mask meaning 'equal'.
     */
    public static final int EQUALITY = 1;

    /**
     * A bit of a search mask meaning 'larger or equal'.
     */
    public static final int START = 2;

    /**
     * A bit of a search mask meaning 'smaller or equal'.
     */
    public static final int END = 4;

    /**
     * A search mask meaning 'between'.
     */
    public static final int RANGE = START | END;

    /**
     * A bit of a search mask meaning 'the condition is always false'.
     */
    public static final int ALWAYS_FALSE = 8;

    private final Column column;
    /**
     * see constants in {@link Comparison}
     */
    private final int compareType;

    private final Expression expression;
    private List<Expression> expressionList;
    private Query expressionQuery;

    /**
     * @param compareType the comparison type, see constants in
     *                    {@link Comparison}
     */
    private IndexCondition(int compareType, ExpressionColumn column,
                           Expression expression) {
        this.compareType = compareType;
        this.column = column == null ? null : column.getColumn();
        this.expression = expression;
    }

    /**
     * Create an index condition with the given parameters.
     *
     * @param compareType the comparison type, see constants in
     *                    {@link Comparison}
     * @param column      the column
     * @param expression  the expression
     * @return the index condition
     */
    public static IndexCondition get(int compareType, ExpressionColumn column,
                                     Expression expression) {
        return new IndexCondition(compareType, column, expression);
    }

    /**
     * Create an index condition with the compare type IN_LIST and with the
     * given parameters.
     *
     * @param column the column
     * @param list   the expression list
     * @return the index condition
     */
    public static IndexCondition getInList(ExpressionColumn column,
                                           List<Expression> list) {
        IndexCondition cond = new IndexCondition(Comparison.IN_LIST, column, null);
        cond.expressionList = list;
        return cond;
    }

    /**
     * Create an index condition with the compare type IN_QUERY and with the
     * given parameters.
     *
     * @param column the column
     * @param query  the select statement
     * @return the index condition
     */
    public static IndexCondition getInQuery(ExpressionColumn column, Query query) {
        IndexCondition cond = new IndexCondition(Comparison.IN_QUERY, column, null);
        cond.expressionQuery = query;
        return cond;
    }

    /**
     * Get the current value of the expression.
     *
     * @param session the session
     * @return the value
     */
    public Value getCurrentValue(Session session) {
        return expression.getValue(session);
    }

    /**
     * Get the current value list of the expression. The value list is of the
     * same type as the column, distinct, and sorted.
     *
     * @param session the session
     * @return the value list
     */
    public Value[] getCurrentValueList(Session session) {
        HashSet<Value> valueSet = new HashSet<Value>();
        for (Expression e : expressionList) {
            Value v = e.getValue(session);
            v = column.convert(v);
            valueSet.add(v);
        }
        Value[] array = new Value[valueSet.size()];
        valueSet.toArray(array);
        final CompareMode mode = session.getDatabase().getCompareMode();
        Arrays.sort(array, new Comparator<Value>() {
            @Override
            public int compare(Value o1, Value o2) {
                return o1.compareTo(o2, mode);
            }
        });
        return array;
    }

    /**
     * Get the current result of the expression. The rows may not be of the same
     * type, therefore the rows may not be unique.
     *
     * @return the result
     */
    public ResultInterface getCurrentResult() {
        return expressionQuery.query(0);
    }

    /**
     * Get the SQL snippet of this comparison.
     *
     * @return the SQL snippet
     */
    public String getSQL() {
        if (compareType == Comparison.FALSE) {
            return "FALSE";
        }
        StatementBuilder buff = new StatementBuilder();
        buff.append(column.getSQL());
        switch (compareType) {
            case Comparison.EQUAL:
                buff.append(" = ");
                break;
            case Comparison.EQUAL_NULL_SAFE:
                buff.append(" IS ");
                break;
            case Comparison.BIGGER_EQUAL:
                buff.append(" >= ");
                break;
            case Comparison.BIGGER:
                buff.append(" > ");
                break;
            case Comparison.SMALLER_EQUAL:
                buff.append(" <= ");
                break;
            case Comparison.SMALLER:
                buff.append(" < ");
                break;
            case Comparison.IN_LIST:
                buff.append(" IN(");
                for (Expression e : expressionList) {
                    buff.appendExceptFirst(", ");
                    buff.append(e.getSQL());
                }
                buff.append(')');
                break;
            case Comparison.IN_QUERY:
                buff.append(" IN(");
                buff.append(expressionQuery.getPlanSQL());
                buff.append(')');
                break;
            default:
                DbException.throwInternalError("type=" + compareType);
        }
        if (expression != null) {
            buff.append(expression.getSQL());
        }
        return buff.toString();
    }

    /**
     * Get the comparison bit mask.
     *
     * @param indexConditions all index conditions
     * @return the mask
     */
    public int getMask(ArrayList<IndexCondition> indexConditions) {
        switch (compareType) {
            case Comparison.FALSE:
                return ALWAYS_FALSE;
            case Comparison.EQUAL:
            case Comparison.EQUAL_NULL_SAFE:
                return EQUALITY;
            case Comparison.IN_LIST:
            case Comparison.IN_QUERY:
                if (indexConditions.size() > 1) {
                    if (!Table.TABLE.equals(column.getTable().getTableType())) {
                        // if combined with other conditions,
                        // IN(..) can only be used for regular tables
                        // test case:
                        // create table test(a int, b int, primary key(id, name));
                        // create unique index c on test(b, a);
                        // insert into test values(1, 10), (2, 20);
                        // select * from (select * from test)
                        // where a=1 and b in(10, 20);
                        return 0;
                    }
                }
                return EQUALITY;
            case Comparison.BIGGER_EQUAL:
            case Comparison.BIGGER:
                return START;
            case Comparison.SMALLER_EQUAL:
            case Comparison.SMALLER:
                return END;
            default:
                throw DbException.throwInternalError("type=" + compareType);
        }
    }

    /**
     * Check if the result is always false.
     *
     * @return true if the result will always be false
     */
    public boolean isAlwaysFalse() {
        return compareType == Comparison.FALSE;
    }

    /**
     * Check if this index condition is of the type column larger or equal to
     * value.
     *
     * @return true if this is a start condition
     */
    public boolean isStart() {
        switch (compareType) {
            case Comparison.EQUAL:
            case Comparison.EQUAL_NULL_SAFE:
            case Comparison.BIGGER_EQUAL:
            case Comparison.BIGGER:
                return true;
            default:
                return false;
        }
    }

    /**
     * Check if this index condition is of the type column smaller or equal to
     * value.
     *
     * @return true if this is a end condition
     */
    public boolean isEnd() {
        switch (compareType) {
            case Comparison.EQUAL:
            case Comparison.EQUAL_NULL_SAFE:
            case Comparison.SMALLER_EQUAL:
            case Comparison.SMALLER:
                return true;
            default:
                return false;
        }
    }

    public int getCompareType() {
        return compareType;
    }

    /**
     * Get the referenced column.
     *
     * @return the column
     */
    public Column getColumn() {
        return column;
    }

    /**
     * Check if the expression can be evaluated.
     *
     * @return true if it can be evaluated
     */
    public boolean isEvaluatable() {
        if (expression != null) {
            return expression.isEverything(ExpressionVisitor.EVALUATABLE_VISITOR);
        }
        if (expressionList != null) {
            for (Expression e : expressionList) {
                if (!e.isEverything(ExpressionVisitor.EVALUATABLE_VISITOR)) {
                    return false;
                }
            }
            return true;
        }
        return expressionQuery.isEverything(ExpressionVisitor.EVALUATABLE_VISITOR);
    }

}
