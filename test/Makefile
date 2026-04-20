WORKDIR  := .
ROOT_POM := pom.xml
JAR      := host/target/host-0.1.0-jar-with-dependencies.jar

MVN             ?= mvn
MAVEN_FLAGS     ?= -DskipTests
ENCLAVE_PROFILE ?= -Pnative

.PHONY: all build clean common enclave host test test-console test-method help

all: build

help:
	@echo "Make targets:"
	@echo "  make [all|build]   - Orchestrates the full build process: common library, native enclave, and host application."
	@echo "  make common        - Compiles the shared Java API and data models used by both host and enclave."
	@echo "  make enclave       - Builds the native SGX enclave using GraalVM Native Image and signs the resulting binary."
	@echo "  make host          - Compiles the host-side application, bundling the signed enclave binary into resources."
	@echo "  make test          - Runs the JUnit test suite via Maven Surefire (redirects output, use for CI/automated checks)."
	@echo "  make test-debug    - Runs JUnit tests via ConsoleLauncher to bypass Surefire and see raw enclave/native logs."
	@echo "  make test-method   - Runs a specific test method via ConsoleLauncher. Usage: make test-method CLASS=TestClassName METHOD=methodName"
	@echo "  make clean         - Removes all build artifacts, target directories, and temporary native-image files."
	@echo ""
	@echo "Variables:"
	@echo "  MVN=<path>                Override Maven binary (default: mvn)"
	@echo "  MAVEN_FLAGS='<flags>'     Extra flags (default: $(MAVEN_FLAGS))"
	@echo "  ENCLAVE_PROFILE='<flags>' Profile(s) to pass while building enclave"

build: common enclave host

clean:
	$(MVN) -f $(ROOT_POM) clean

common:
	$(MVN) -f $(ROOT_POM) -pl common -am $(MAVEN_FLAGS) package

enclave:
	$(MVN) -f $(ROOT_POM) -pl enclave -am $(MAVEN_FLAGS) $(ENCLAVE_PROFILE) package

host:
	$(MVN) -f $(ROOT_POM) -pl host -am $(MAVEN_FLAGS) package

test:
	sudo $(MVN) -f $(ROOT_POM) test -pl host -am

test-debug:
	@TEST_CP=$$($(MVN) -f host/pom.xml -q dependency:build-classpath -DincludeScope=test -Dmdep.outputFile=/dev/stdout 2>/dev/null); \
	sudo java -cp "$(JAR):host/target/test-classes:$$TEST_CP" \
		org.junit.platform.console.ConsoleLauncher execute \
		--scan-classpath \
		--disable-banner \
		--details=testfeed

test-method:
	@TEST_CP=$$($(MVN) -f host/pom.xml -q dependency:build-classpath -DincludeScope=test -Dmdep.outputFile=/dev/stdout 2>/dev/null); \
	sudo java -cp "$(JAR):host/target/test-classes:$$TEST_CP" \
		org.junit.platform.console.ConsoleLauncher execute \
		--select-method=org.apache.teaclave.javasdk.test.host.$(CLASS)#$(METHOD) \
		--disable-banner \
		--details=testfeed
