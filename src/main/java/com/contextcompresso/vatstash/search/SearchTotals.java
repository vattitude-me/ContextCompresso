package com.contextcompresso.vatstash.search;

public record SearchTotals(
        int returnedTokens,
        long sourceFileTokens,
        int filesMatched,
        int chunksReturned,
        int omittedByBudget
) {
}
