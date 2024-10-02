/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.esql.optimizer;

import org.elasticsearch.xpack.esql.common.Failures;
import org.elasticsearch.xpack.esql.core.expression.Attribute;
import org.elasticsearch.xpack.esql.core.expression.Expressions;
import org.elasticsearch.xpack.esql.optimizer.rules.PlanConsistencyChecker;
import org.elasticsearch.xpack.esql.plan.physical.FieldExtractExec;
import org.elasticsearch.xpack.esql.plan.physical.PhysicalPlan;

import static org.elasticsearch.xpack.esql.common.Failure.fail;

/** Physical plan verifier. */
public final class PhysicalVerifier {

    public static final PhysicalVerifier INSTANCE = new PhysicalVerifier();
    private static final PlanConsistencyChecker<PhysicalPlan> DEPENDENCY_CHECK = new PlanConsistencyChecker<>();

    private PhysicalVerifier() {}

    /** Verifies the physical plan. */
    public Failures verify(PhysicalPlan plan) {
        Failures failures = new Failures();
        Failures dependencyFailures = new Failures();

        plan.forEachDown(p -> {
            if (p instanceof FieldExtractExec fieldExtractExec) {
                Attribute sourceAttribute = fieldExtractExec.sourceAttribute();
                if (sourceAttribute == null) {
                    failures.add(
                        fail(
                            fieldExtractExec,
                            "Need to add field extractor for [{}] but cannot detect source attributes from node [{}]",
                            Expressions.names(fieldExtractExec.attributesToExtract()),
                            fieldExtractExec.child()
                        )
                    );
                    return;
                }
            }

            // Needs to come after the FieldExtractExec check, as that one prevents FieldExtractExec from having a null source attribute,
            // which the dependency checker cannot handle.
            DEPENDENCY_CHECK.checkPlan(p, dependencyFailures);
        });

        if (dependencyFailures.hasFailures()) {
            throw new IllegalStateException(dependencyFailures.toString());
        }

        return failures;
    }
}
