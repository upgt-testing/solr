#!/usr/bin/env python3
"""
Script to find and run all NO_UPGRADE tests from ProcessBased test classes.

Usage:
  ./run_all_no_upgrade.py                     # Run all NO_UPGRADE tests
  ./run_all_no_upgrade.py --test ClassName#methodName  # Run specific test
  ./run_all_no_upgrade.py --class ClassName   # Run all tests from a specific class
"""

import argparse
import os
import re
import subprocess
import sys
from pathlib import Path
from datetime import datetime


def find_process_based_test_files(root_dir):
    """
    Find all Java test files ending with '_ProcessBased.java'.

    Args:
        root_dir: Root directory to search from

    Returns:
        List of paths to ProcessBased test files
    """
    test_files = []
    for root, dirs, files in os.walk(root_dir):
        for file in files:
            if file.endswith('_ProcessBased.java'):
                test_files.append(os.path.join(root, file))
    return test_files


def extract_test_methods(file_path):
    """
    Extract all test methods ending with '_NO_UPGRADE' from a Java test file.

    Args:
        file_path: Path to the Java test file

    Returns:
        List of test method names
    """
    test_methods = []

    try:
        with open(file_path, 'r', encoding='utf-8') as f:
            content = f.read()

        # Pattern to match test methods ending with _NO_UPGRADE
        # Look for @Test annotation followed by method definition
        pattern = r'@Test[^}]*?public\s+void\s+(\w+_NO_UPGRADE)\s*\([^)]*\)'

        matches = re.findall(pattern, content, re.DOTALL)
        test_methods.extend(matches)

    except Exception as e:
        print(f"Error reading file {file_path}: {e}", file=sys.stderr)

    return test_methods


def get_class_name_from_path(file_path):
    """
    Extract the fully qualified class name from a Java file path.

    Args:
        file_path: Path to the Java file

    Returns:
        Fully qualified class name
    """
    # Read the file to find the package name
    try:
        with open(file_path, 'r', encoding='utf-8') as f:
            content = f.read()

        # Find package declaration
        package_match = re.search(r'package\s+([\w.]+)\s*;', content)
        package_name = package_match.group(1) if package_match else ''

        # Get class name from file name
        class_name = os.path.basename(file_path).replace('.java', '')

        # Combine package and class name
        if package_name:
            return f"{package_name}.{class_name}"
        else:
            return class_name

    except Exception as e:
        print(f"Error extracting class name from {file_path}: {e}", file=sys.stderr)
        return os.path.basename(file_path).replace('.java', '')


def run_gradle_test(test_class, test_method, solr_start_home, solr_upgrade_home, output_dir, java_home):
    """
    Run a specific test using Gradle and save output to file.

    Args:
        test_class: Fully qualified test class name
        test_method: Test method name
        solr_start_home: Path to solr start home
        solr_upgrade_home: Path to solr upgrade home
        output_dir: Directory to save test output
        java_home: Path to Java home directory

    Returns:
        return_code: Exit code from the test
    """
    # Use dot notation for Gradle test selector (not # which causes silent failures)
    test_spec = f"{test_class}.{test_method}"

    # Create a safe filename from class and method name
    class_short_name = test_class.split('.')[-1]  # Get just the class name without package
    output_filename = f"{class_short_name}_{test_method}.log"
    output_path = os.path.join(output_dir, output_filename)

    # Determine which gradle module the test belongs to
    if 'modules.ltr' in test_class:
        gradle_module = ':solr:modules:ltr:test'
    else:
        gradle_module = ':solr:core:test'

    # System properties are forwarded to test JVM via randomization.gradle configuration
    # Disable security manager since ProcessBased tests need to access external file systems
    cmd = [
        './gradlew', gradle_module,
        f'--tests={test_spec}',
        f'-Dsolr.start.home={solr_start_home}',
        f'-Dsolr.upgrade.home={solr_upgrade_home}',
        '-Ptests.useSecurityManager=false'
    ]

    print(f"\n{'='*80}")
    print(f"Running: {test_spec}")
    print(f"Command: {' '.join(cmd)}")
    print(f"JAVA_HOME: {java_home}")
    print(f"Output: {output_path}")
    print(f"{'='*80}\n")

    try:
        # Set up environment with correct JAVA_HOME
        env = os.environ.copy()
        env['JAVA_HOME'] = java_home

        # Run the command and capture output
        with open(output_path, 'w', encoding='utf-8') as log_file:
            # Write header to log file
            log_file.write(f"Test: {test_spec}\n")
            log_file.write(f"Command: {' '.join(cmd)}\n")
            log_file.write(f"JAVA_HOME: {java_home}\n")
            log_file.write(f"Started: {datetime.now().strftime('%Y-%m-%d %H:%M:%S')}\n")
            log_file.write(f"{'='*80}\n\n")
            log_file.flush()

            # Run the test with output going to both console and file
            result = subprocess.run(
                cmd,
                stdout=subprocess.PIPE,
                stderr=subprocess.STDOUT,
                text=True,
                env=env
            )

            # Write output to file
            log_file.write(result.stdout)
            log_file.write(f"\n{'='*80}\n")
            log_file.write(f"Finished: {datetime.now().strftime('%Y-%m-%d %H:%M:%S')}\n")
            log_file.write(f"Exit code: {result.returncode}\n")

        # Print output to console as well
        print(result.stdout)

        status = "PASSED" if result.returncode == 0 else "FAILED"
        print(f"\nTest {status}: {test_spec} (exit code: {result.returncode})")
        print(f"Output saved to: {output_path}")

        return result.returncode

    except Exception as e:
        error_msg = f"Error running test {test_spec}: {e}"
        print(error_msg, file=sys.stderr)

        # Try to write error to log file
        try:
            with open(output_path, 'a', encoding='utf-8') as log_file:
                log_file.write(f"\n\nERROR: {error_msg}\n")
        except:
            pass

        return -1


def get_java_home():
    """
    Get the path to Java 11+ home directory.

    Returns:
        Path to Java home directory
    """
    # Try to find Java 11+ using java_home command on macOS
    try:
        result = subprocess.run(
            ['/usr/libexec/java_home', '-v', '11'],
            capture_output=True,
            text=True,
            check=True
        )
        java_home = result.stdout.strip()
        print(f"Using Java 11: {java_home}")
        return java_home
    except (subprocess.CalledProcessError, FileNotFoundError):
        # Fallback: check if JAVA_HOME is already set and points to Java 11+
        java_home = os.environ.get('JAVA_HOME')
        if java_home:
            print(f"Using JAVA_HOME from environment: {java_home}")
            return java_home
        else:
            print("ERROR: Could not find Java 11+. Please set JAVA_HOME or install Java 11+", file=sys.stderr)
            sys.exit(1)


def main():
    # Parse command line arguments
    parser = argparse.ArgumentParser(
        description='Run NO_UPGRADE tests from ProcessBased test classes',
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""
Examples:
  %(prog)s
      Run all NO_UPGRADE tests

  %(prog)s --test org.apache.solr.cloud.TestCollectionAPI_ProcessBased#testCollectionAPI_NO_UPGRADE
      Run a specific test method

  %(prog)s --class TestCollectionAPI_ProcessBased
      Run all NO_UPGRADE tests from a specific class
        """
    )
    parser.add_argument('--test', metavar='CLASS#METHOD',
                        help='Run specific test in format: ClassName#methodName or fully.qualified.ClassName#methodName')
    parser.add_argument('--class', dest='test_class', metavar='CLASSNAME',
                        help='Run all NO_UPGRADE tests from a specific class (short or fully qualified name)')

    args = parser.parse_args()

    # Configuration
    root_dir = os.getcwd()
    solr_start_home = '/Users/allenwang/xlab/solr-test-distributions/solr-9.9.0'
    solr_upgrade_home = '/Users/allenwang/xlab/solr-test-distributions/solr-9.9.0'

    # Get Java 11+ home
    java_home = get_java_home()

    # Create output directory with timestamp
    timestamp = datetime.now().strftime('%Y%m%d_%H%M%S')
    output_dir = os.path.join(root_dir, f'test-outputs-no-upgrade_{timestamp}')
    os.makedirs(output_dir, exist_ok=True)

    print(f"Searching for ProcessBased test files in: {root_dir}")
    print(f"Test outputs will be saved to: {output_dir}\n")

    # Find all ProcessBased test files
    test_files = find_process_based_test_files(root_dir)
    print(f"Found {len(test_files)} ProcessBased test files")

    # Collect all test cases
    test_cases = []
    for test_file in test_files:
        print(f"\nProcessing: {test_file}")
        class_name = get_class_name_from_path(test_file)
        test_methods = extract_test_methods(test_file)

        print(f"  Class: {class_name}")
        print(f"  Found {len(test_methods)} NO_UPGRADE test methods: {test_methods}")

        for test_method in test_methods:
            test_cases.append((class_name, test_method))

    # Filter test cases based on command line arguments
    if args.test:
        # Parse --test argument (can be ClassName#method or fully.qualified.ClassName#method)
        if '#' not in args.test:
            print(f"ERROR: --test argument must be in format ClassName#methodName", file=sys.stderr)
            sys.exit(1)

        filter_class, filter_method = args.test.split('#', 1)
        filtered_cases = []
        for class_name, test_method in test_cases:
            # Match either short class name or fully qualified name
            if (class_name == filter_class or class_name.endswith('.' + filter_class)) and test_method == filter_method:
                filtered_cases.append((class_name, test_method))

        if not filtered_cases:
            print(f"ERROR: No test found matching: {args.test}", file=sys.stderr)
            print(f"\nAvailable tests:", file=sys.stderr)
            for class_name, test_method in test_cases[:5]:
                print(f"  {class_name}#{test_method}", file=sys.stderr)
            print(f"  ... and {len(test_cases) - 5} more", file=sys.stderr)
            sys.exit(1)

        test_cases = filtered_cases
        print(f"\nFiltered to specific test: {args.test}")

    elif args.test_class:
        # Filter by class name (short or fully qualified)
        filtered_cases = []
        for class_name, test_method in test_cases:
            if class_name == args.test_class or class_name.endswith('.' + args.test_class):
                filtered_cases.append((class_name, test_method))

        if not filtered_cases:
            print(f"ERROR: No tests found for class: {args.test_class}", file=sys.stderr)
            print(f"\nAvailable classes:", file=sys.stderr)
            classes = sorted(set(c for c, _ in test_cases))
            for cls in classes[:5]:
                print(f"  {cls}", file=sys.stderr)
            print(f"  ... and {len(classes) - 5} more", file=sys.stderr)
            sys.exit(1)

        test_cases = filtered_cases
        print(f"\nFiltered to class: {args.test_class} ({len(test_cases)} tests)")

    print(f"\n{'='*80}")
    print(f"Total test cases to run: {len(test_cases)}")
    print(f"{'='*80}\n")

    # Run all test cases
    results = []
    for i, (test_class, test_method) in enumerate(test_cases, 1):
        print(f"\nRunning test {i}/{len(test_cases)}")
        return_code = run_gradle_test(
            test_class,
            test_method,
            solr_start_home,
            solr_upgrade_home,
            output_dir,
            java_home
        )
        results.append((test_class, test_method, return_code))

    # Print summary
    print(f"\n{'='*80}")
    print("SUMMARY")
    print(f"{'='*80}\n")

    passed = sum(1 for _, _, rc in results if rc == 0)
    failed = sum(1 for _, _, rc in results if rc != 0)

    print(f"Total tests: {len(results)}")
    print(f"Passed: {passed}")
    print(f"Failed: {failed}")
    print(f"\nAll test outputs saved to: {output_dir}")

    if failed > 0:
        print("\nFailed tests:")
        for test_class, test_method, rc in results:
            if rc != 0:
                class_short_name = test_class.split('.')[-1]
                log_file = f"{class_short_name}_{test_method}.log"
                print(f"  - {test_class}#{test_method} (exit code: {rc})")
                print(f"    Log: {os.path.join(output_dir, log_file)}")

    # Exit with non-zero code if any tests failed
    sys.exit(0 if failed == 0 else 1)


if __name__ == '__main__':
    main()
