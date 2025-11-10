#!/bin/bash

# Kotlin Common Library Test Runner
# Usage: ./run_test.sh [test-pattern]

TEST_PATTERN=${1:-"*Test*"}
MODULE=${2:-":standard-api-response"}

echo "🧪 Running tests..."
echo "Module: $MODULE"
echo "Pattern: $TEST_PATTERN"
echo "----------------------------------------"

# Run the tests (console=plain is now set in gradle.properties)
./gradlew $MODULE:test --tests "$TEST_PATTERN"

EXIT_CODE=$?

echo "----------------------------------------"
if [ $EXIT_CODE -eq 0 ]; then
    echo "✅ ALL TESTS PASSED!"
else
    echo "❌ TESTS FAILED (exit code: $EXIT_CODE)"
fi

exit $EXIT_CODE
