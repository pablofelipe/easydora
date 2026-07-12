package messaging

import (
	"fmt"
	"os"
	"path/filepath"

	"github.com/santhosh-tekuri/jsonschema/v5"
)

// Shared boilerplate for this package's *_contract_test.go files --
// deduplicates schema-loading, not a new abstraction: every contract test
// still owns its own assertion and its own example payload.
func loadSchema(fileName string) (*jsonschema.Schema, error) {
	path, err := resolveSchemaPath(fileName)
	if err != nil {
		return nil, err
	}
	compiler := jsonschema.NewCompiler()
	return compiler.Compile(path)
}

func resolveSchemaPath(fileName string) (string, error) {
	// go test's working directory is always the package directory
	// (internal/messaging), three levels below the repo root
	// (inventory-service/internal/messaging).
	fromPackageDir := filepath.Join("..", "..", "..", "schemas", "json", fileName)
	if _, err := os.Stat(fromPackageDir); err == nil {
		return fromPackageDir, nil
	}
	fromRepoRoot := filepath.Join("schemas", "json", fileName)
	if _, err := os.Stat(fromRepoRoot); err == nil {
		return fromRepoRoot, nil
	}
	return "", fmt.Errorf("shared schema %s not found relative to internal/messaging or repo root", fileName)
}
