package com.dipalma.whatif.util;

import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.ConditionalExpr;
import com.github.javaparser.ast.stmt.*;

public final class ComplexityUtils {
    private ComplexityUtils() {}

    /**
     * Count decision points inside a callable (if, for, foreach, while, do, ternary, switch cases).
     */
    public static int countDecisionPoints(CallableDeclaration<?> callable) {
        if (callable == null) return 0;
        int count = 0;
        try {
            count += callable.findAll(IfStmt.class).size();
            count += callable.findAll(ForStmt.class).size();
            count += callable.findAll(ForEachStmt.class).size();
            count += callable.findAll(WhileStmt.class).size();
            count += callable.findAll(DoStmt.class).size();
            count += callable.findAll(ConditionalExpr.class).size();
            int switchCases = callable.findAll(SwitchStmt.class).stream().mapToInt(sw -> sw.getEntries().size()).sum();
            count += switchCases;
        } catch (Exception e) {
            return 0;
        }
        return count;
    }

    /**
     * Compute cyclomatic complexity for a MethodDeclaration (standalone snippet parsing).
     */
    public static int computeCyclomatic(MethodDeclaration m) {
        if (m == null) return 1;
        final int[] c = {1};
        m.walk(node -> {
            if (node instanceof IfStmt || node instanceof ForStmt ||
                    node instanceof WhileStmt || node instanceof DoStmt ||
                    node instanceof SwitchEntry || node instanceof CatchClause ||
                    node instanceof ConditionalExpr) {
                c[0]++;
            }
        });
        return c[0];
    }
}
