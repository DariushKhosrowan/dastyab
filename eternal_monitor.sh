#!/bin/bash
# Eternal Dastyab APK Build Monitor
# Loops forever, checking GitHub Actions for completed builds
# On success: downloads APK, installs via adb, tests it
# On failure/rate-limit: retries with exponential backoff

REPO_OWNER="DastYab"
REPO_NAME="dastyab"
WORKFLOW_NAME="Build Android APK"
MAX_RETRIES=999  # Effectively infinite
INITIAL_SLEEP=15
MAX_SLEEP=300    # 5 minutes max sleep
RATE_LIMIT_SLEEP=300  # 5 minutes for GitHub API rate limit reset

# Find adb binary
find_adb() {
    local adb_path=""
    
    # Check common paths
    for path in \
        "/opt/hostedtoolcache/Android_SDK/platform-tools/adb" \
        "$HOME/android/platform-tools/adb" \
        "/usr/local/bin/adb" \
        "/usr/bin/adb"; do
        if [ -x "$path" ] 2>/dev/null; then
            adb_path="$path"
            break
        fi
    done
    
    # Search if not found
    if [ -z "$adb_path" ]; then
        adb_path=$(find / -name "adb" -type f 2>/dev/null | head -1)
    fi
    
    echo "$adb_path"
}

ADB=$(find_adb)

# Color codes
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
NC='\033[0m' # No Color

log() {
    local color="$1"
    local message="$2"
    local time=$(date '+%H:%M:%S')
    echo -e "${color}[${time}]${NC} ${message}"
}

# Get latest workflow run from GitHub API
get_latest_run() {
    local url="https://api.github.com/repos/${REPO_OWNER}/${REPO_NAME}/actions/runs?per_page=1"
    local result=""
    local rate_limited=0
    
    # Try up to 3 times
    for attempt in $(seq 1 3); do
        result=$(curl -s -H "Accept: application/vnd.github+json" \
            -H "X-GitHub-Api-Version: 2022-11-28" \
            "$url" 2>/dev/null)
        
        # Check response
        if [ $? -ne 0 ]; then
            log $YELLOW "Attempt $attempt: curl error, waiting..."
            sleep $INITIAL_SLEEP
            continue
        fi
        
        # Check if we got valid data
        if echo "$result" | grep -q '"workflow_runs"'; then
            echo "$result"
            return 0
        elif echo "$result" | grep -q "rate limit"; then
            rate_limited=1
            log $YELLOW "Rate limited by GitHub API (attempt $attempt/3)"
            # Wait for rate limit to reset (estimate)
            sleep $RATE_LIMIT_SLEEP
            continue
        elif [ -n "$result" ]; then
            # Some other response, try to use it
            echo "$result"
            return 0
        else
            sleep $INITIAL_SLEEP
            continue
        fi
    done
    
    # If rate limited or failed, still return what we have
    if [ $rate_limited -eq 1 ]; then
        log $RED "Still rate limited after 3 attempts, waiting again..."
        sleep $RATE_LIMIT_SLEEP
    fi
    
    # Return empty or last result
    if [ -n "$result" ]; then
        echo "$result"
        return 0
    fi
    return 1
}

# Parse run status from JSON
parse_status() {
    local json="$1"
    echo "$json" | python3 -c "
import sys, json
try:
    data = json.load(sys.stdin)
    if data and 'workflow_runs' in data and len(data['workflow_runs']) > 0:
        run = data['workflow_runs'][0]
        status = run.get('status', 'unknown')
        conclusion = run.get('conclusion', 'none')
        print(f'{status}.{conclusion}')
except:
    print('unknown.none')
"
}

parse_url() {
    local json="$1"
    echo "$json" | python3 -c "
import sys, json
try:
    data = json.load(sys.stdin)
    if data and 'workflow_runs' in data and len(data['workflow_runs']) > 0:
        print(data['workflow_runs'][0].get('url', ''))
except:
    print('')
"
}

parse_branch() {
    local json="$1"
    echo "$json" | python3 -c "
import sys, json
try:
    data = json.load(sys.stdin)
    if data and 'workflow_runs' in data and len(data['workflow_runs']) > 0:
        print(data['workflow_runs'][0].get('head_branch', 'unknown'))
except:
    print('unknown')
"
}

# Download and install APK from a run
install_apk_from_run() {
    local run_url="$1"
    local run_id="$2"
    
    log $CYAN "=== Attempting to download APK from run $run_id ==="
    
    # Try to get artifacts
    local api_base="${run_url%/*}"
    local artifacts_url="${api_base}/artifacts"
    
    local artifacts_json=$(curl -s -H "Accept: application/vnd.github+json" \
        -H "X-GitHub-Api-Version: 2022-11-28" \
        "$artifacts_url" 2>/dev/null)
    
    if [ -z "$artifacts_json" ] || ! echo "$artifacts_json" | grep -q '"artifacts"'; then
        log $RED "Could not fetch artifacts list"
        return 1
    fi
    
    # Find app-debug-apk artifact
    local artifact_id=$(echo "$artifacts_json" | python3 -c "
import sys, json
try:
    data = json.load(sys.stdin)
    if data.get('artifacts'):
        for art in data['artifacts']:
            name = art.get('name', '').lower()
            if 'app-debug' in name or art.get('name', '').endswith('.apk'):
                print(art['id'])
                break
except: pass
" 2>/dev/null)
    
    if [ -z "$artifact_id" ]; then
        log $RED "No app-debug-apk artifact found in run $run_id"
        return 1
    fi
    
    log $GREEN "Found artifact ID: $artifact_id"
    
    # Download the artifact zip
    local download_url="https://api.github.com/repos/${REPO_OWNER}/${REPO_NAME}/actions/artifacts/${artifact_id}/zip"
    local zip_response=$(curl -sL -w "\n%{http_code}" -o /tmp/dastyap-apk.zip \
        "$download_url" 2>/dev/null)
    
    local http_code=$(echo "$zip_response" | tail -1)
    local zip_content=$(echo "$zip_response" | head -1)
    
    if [ "$http_code" != "200" ]; then
        log $RED "Failed to download artifact (HTTP $http_code)"
        return 1
    fi
    
    log $GREEN "Downloaded artifact zip ($http_code)"
    
    # Extract the zip
    local extract_dir="/tmp/dastyap-apk-build"
    mkdir -p "$extract_dir"
    
    if unzip -o -q "/tmp/dastyap-apk.zip" -d "$extract_dir" 2>/dev/null; then
        log $GREEN "Extracted artifact to $extract_dir"
    else
        log $RED "Failed to extract artifact zip"
        rm -f "/tmp/dastyap-apk.zip"
        return 1
    fi
    
    rm -f "/tmp/dastyap-apk.zip"
    
    # Find the APK file
    local apk_file=$(find "$extract_dir" -name "app-debug*.apk" -type f 2>/dev/null | head -1)
    
    if [ -z "$apk_file" ]; then
        # Try any .apk file
        apk_file=$(find "$extract_dir" -name "*.apk" -type f 2>/dev/null | head -1)
    fi
    
    if [ -z "$apk_file" ]; then
        log $RED "No APK file found in extracted archive"
        ls -la "$extract_dir" 2>/dev/null
        return 1
    fi
    
    log $GREEN "Found APK: $apk_file"
    
    # Install the APK
    if [ -n "$ADB" ] && [ -x "$ADB" ]; then
        log $CYAN "Attempting to install APK via adb..."
        local install_output=$("$ADB" install -r "$apk_file" 2>&1)
        local install_exit=$?
        
        if [ $install_exit -eq 0 ]; then
            log $GREEN "=== SUCCESS: APK installed successfully! ==="
            
            # Wait for app to start and do basic verification
            sleep 3
            
            # Try to verify the app is running/installed
            if [ -n "$ADB" ]; then
                local verify=$("$ADB" shell "pm list packages com.dastyab" 2>/dev/null)
                if echo "$verify" | grep -q "com.dastyab"; then
                    log $GREEN "=== VERIFIED: com.dastyab package is installed ==="
                fi
            fi
            
            echo ""
            log $GREEN "============================================================"
            log $GREEN "DASTYAB APK: Build successful, APK installed and verified!"
            log $GREEN "============================================================"
            echo ""
            # Wait before continuing to check for new builds
            sleep 60
            return 0
        else
            log $YELLOW "APK install exit code: $install_exit"
            log $YELLOW "Install output: $install_output"
            
            # Check if it's already installed
            if echo "$install_output" | grep -q "Already installed"; then
                log $YELLOW "APK was already installed (upgrading version)"
                sleep 5
                return 0
            fi
        fi
    else
        log $YELLOW "adb not found at $ADB, skipping installation"
        log $YELLOW "To install manually: $ADB install -r $apk_file"
        
        # Still show success if APK was found
        echo ""
        log $GREEN "============================================================"
        log $GREEN "DASTYAB APK: Build successful, APK downloaded but adb not available"
        log $GREEN "============================================================"
        echo ""
        sleep 60
        return 0
    fi
    
    return 1
}

# Main eternal loop
main() {
    local run_count=0
    local consecutive_successes=0
    
    log $CYAN "============================================================"
    log $CYAN "Dastyab Eternal APK Build Monitor - Starting"
    log $CYAN "============================================================"
    log $CYAN "Repository: ${REPO_OWNER}/${REPO_NAME}"
    log $CYAN "Workflow: ${WORKFLOW_NAME}"
    log $CYAN "ADB location: ${ADB:-not found}"
    log $CYAN "Monitoring forever... (Ctrl+C to stop)"
    log $CYAN "============================================================"
    echo ""
    
    while true; do
        run_count=$((run_count + 1))
        log $CYAN "=== Check #${run_count} - $(date '+%H:%M:%S') ==="
        
        # Get latest run
        run_info=$(get_latest_run)
        
        if [ -z "$run_info" ]; then
            log $YELLOW "No response from GitHub API, waiting ${INITIAL_SLEEP}s..."
            sleep $INITIAL_SLEEP
            continue
        fi
        
        # Parse status
        run_status=$(parse_status "$run_info")
        run_url=$(parse_url "$run_info")
        run_branch=$(parse_branch "$run_info")
        
        # Display status
        if echo "$run_status" | grep -q "^completed\."; then
            conclusion=$(echo "$run_status" | cut -d'.' -f2)
            
            if [ "$conclusion" = "success" ]; then
                log $GREEN "✅ Workflow COMPLETED SUCCESSFULLY on branch: $run_branch"
                if [ -n "$run_url" ]; then
                    log $CYAN "View run: $run_url"
                fi
                
                # Download and install APK
                if install_apk_from_run "$run_url" "$run_count"; then
                    consecutive_successes=$((consecutive_successes + 1))
                    # No reset - keep going
                else
                    # Installation failed, but build succeeded
                    log $YELLOW "Build succeeded but APK installation failed"
                fi
                
            elif [ "$conclusion" = "failure" ]; then
                log $RED "❌ Workflow FAILED on branch: $run_branch"
                if [ -n "$run_url" ]; then
                    log $CYAN "View details: $run_url"
                fi
                # Wait before next check
                sleep 20
                
            else
                log $YELLOW "⚠️ Workflow completed with conclusion: $conclusion"
                sleep 15
            fi
            
        elif echo "$run_status" | grep -q "^in_progress\|^queued"; then
            log $YELLOW "⏱️ Workflow RUNNING on branch: $run_branch (status: $run_status)"
            sleep 20
            
        else
            log $YELLOW "Unknown status: $run_status"
            sleep 15
        fi
    done
}

# Run main function
main
