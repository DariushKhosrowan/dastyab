#!/bin/bash
# Continuous monitor for Dastyab Android APK builds
# Checks GitHub Actions API, downloads and installs APK on success
# Retries on failures with exponential backoff

# Configuration
REPO_OWNER="DastYab"
REPO_NAME="dastyab"
WORKFLOW_NAME="Build Android APK"
MAX_RETRIES=15
BASE_SLEEP=20
ADB_PATH="/opt/hostedtoolcache/Android_SDK/platform-tools/adb"

# Colors for output (if supported)
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

echo "============================================================"
echo "Dastyab APK Build Monitor - Starting"
echo "============================================================"
echo ""

retry_count=0
last_run_id=""

get_latest_run() {
    # Try multiple approaches to get the latest workflow run
    local result=""
    
    # Approach 1: Use curl with API
    result=$(curl -s -H "Accept: application/vnd.github+json" \
        -H "X-GitHub-Api-Version: 2022-11-28" \
        "https://api.github.com/repos/${REPO_OWNER}/${REPO_NAME}/actions/runs?per_page=1" 2>/dev/null)
    
    if [ -n "$result" ] && [ "$result" != "[]" ]; then
        echo "$result"
        return 0
    fi
    
    # Approach 2: Try gh if available
    if command -v gh &>/dev/null; then
        result=$(gh run list --limit 1 --json status,conclusion,headBranch,workflowName,url 2>/dev/null)
        if [ -n "$result" ] && [ "$result" != "[]" ]; then
            echo "$result"
            return 0
        fi
    fi
    
    return 1
}

parse_run_status() {
    local json_data="$1"
    if [ -z "$json_data" ]; then
        echo "unknown.none"
        return 1
    fi
    
    python3 -c "
import sys, json
try:
    data = json.load(sys.stdin)
    if data and len(data) > 0:
        run = data[0]
        status = run.get('status', 'unknown')
        conclusion = run.get('conclusion', 'none')
        print(f'{status}.{conclusion}')
    else:
        print('unknown.none')
except:
    print('unknown.none')
" <<< "$json_data" 2>/dev/null
}

parse_run_url() {
    local json_data="$1"
    if [ -z "$json_data" ]; then
        echo ""
        return 1
    fi
    
    python3 -c "
import sys, json
try:
    data = json.load(sys.stdin)
    if data and len(data) > 0:
        print(data[0].get('url', ''))
    else:
        print('')
except:
    print('')
" <<< "$json_data" 2>/dev/null
}

install_apk() {
    local apk_file="$1"
    if [ ! -f "$apk_file" ]; then
        echo "❌ APK file not found: $apk_file"
        return 1
    fi
    
    local apk_size=$(stat -c%s "$apk_file" 2>/dev/null || echo "0")
    echo "📱 APK found: $(basename "$apk_file") ($apk_size bytes)"
    
    # Try adb install
    if [ -x "$ADB_PATH" ]; then
        echo "🔧 Attempting adb install..."
        local install_output=$("$ADB_PATH" install -r "$apk_file" 2>&1)
        local install_exit=$?
        
        if [ $install_exit -eq 0 ]; then
            echo -e "✅ ${GREEN}APK installed successfully!${NC}"
            return 0
        else
            echo -e "⚠️ ${YELLOW}adb install exit code: $install_exit${NC}"
            echo "   Output: $install_output"
            
            # Check if already installed
            if echo "$install_output" | grep -q "Already installed"; then
                echo -e "   ${YELLOW}APK was already installed (upgrading)${NC}"
                return 0
            fi
        fi
    else
        echo -e "⚠️ ${YELLOW}adb not found at $ADB_PATH${NC}"
        echo "   Looking for adb in PATH..."
        which adb 2>/dev/null || echo "   adb not in PATH"
    fi
    
    # Try alternative install methods
    echo "🔄 Trying alternative install methods..."
    
    # Find adb anywhere
    local adb_found=$(find / -name "adb" -type f 2>/dev/null | head -1)
    if [ -n "$adb_found" ]; then
        echo "   Found adb: $adb_found"
        local install_output=$("$adb_found" install -r "$apk_file" 2>&1)
        local install_exit=$?
        if [ $install_exit -eq 0 ]; then
            echo -e "✅ ${GREEN}APK installed successfully via alternative adb!${NC}"
            return 0
        fi
    fi
    
    echo -e "❌ ${RED}Failed to install APK${NC}"
    return 1
}

download_artifact() {
    local run_url="$1"
    local run_id="$2"
    
    echo "⬇️  Downloading artifact for run $run_id..."
    
    # Try to download the artifact
    local download_result=""
    
    # Approach 1: gh run download
    if command -v gh &>/dev/null; then
        download_result=$(gh run download "$run_url" --pattern "app-debug-apk/*" 2>&1)
        if [ $? -eq 0 ]; then
            echo "   Artifact download initiated via gh"
        fi
    fi
    
    # Approach 2: Use curl with artifact API
    local api_url="${run_url%/*}/artifacts"
    local artifacts_json=$(curl -s -H "Accept: application/vnd.github+json" \
        -H "X-GitHub-Api-Version: 2022-11-28" \
        "$api_url" 2>/dev/null)
    
    if [ -n "$artifacts_json" ]; then
        # Find release APK artifact ID (fallback to debug artifact if present)
        local artifact_id=$(echo "$artifacts_json" | python3 -c "
import sys, json
try:
    data = json.load(sys.stdin)
    if data and 'artifacts' in data:
        arts = data['artifacts']
        for target in ('dastyab-release-android-apk', 'app-debug-apk'):
            for art in arts:
                if target in art.get('name', '').lower():
                    print(art['id'])
                    raise SystemExit(0)
        for art in arts:
            if art.get('name', '').endswith('.apk'):
                print(art['id'])
                break
except Exception:
    pass
" 2>/dev/null)
        
        if [ -n "$artifact_id" ]; then
            # Download the artifact
            local download_api="https://api.github.com/repos/${REPO_OWNER}/${REPO_NAME}/actions/artifacts/${artifact_id}/zip"
            local zip_data=$(curl -sL -o "dastyab-apk.zip" "$download_api" -w "%{http_code}" 2>/dev/null)
            
            if [ "$zip_data" = "200" ]; then
                unzip -o -q dastyab-apk.zip -d . 2>/dev/null
                rm -f dastyab-apk.zip
                echo "   ✅ Artifact downloaded and extracted"
                
                # Find the APK file (prefer signed release APK)
                local apk_file=$(find . \( -name "DastYab-*-android.apk" -o -name "app-release*.apk" -o -name "app-debug*.apk" \) -type f 2>/dev/null | head -1)
                if [ -n "$apk_file" ]; then
                    echo "   📄 Found: $apk_file"
                    if [ -f "scripts/android-verify-apk.sh" ]; then
                        bash scripts/android-verify-apk.sh "$apk_file" --static-only || return 1
                    fi
                    install_apk "$apk_file"
                    return 0
                else
                    echo "   ⚠️ No APK file found in archive"
                fi
            else
                echo "   ⚠️ Failed to download artifact (HTTP: $zip_data)"
            fi
        else
            echo "   ⚠️ No app-debug-apk artifact found in run"
            echo "   Available artifacts:"
            echo "$artifacts_json" | python3 -c "
import sys, json
try:
    data = json.load(sys.stdin)
    if data and 'artifacts' in data:
        for art in data['artifacts'][:10]:
            print(f'   - {art.get(\"name\", \"unknown\")} (ID: {art.get(\"id\", \"unknown\")})')
except:
    pass
" 2>/dev/null
        fi
    else
        echo "   ⚠️ Could not fetch artifacts list"
    fi
    
    return 1
}

echo "Monitoring for: $WORKFLOW on $REPO_OWNER/$REPO_NAME"
echo "Max retries: $MAX_RETRIES"
echo "Sleep between checks: $BASE_SLEEP seconds"
echo ""
echo "============================================================"

while [ $retry_count -lt $MAX_RETRIES ]; do
    # Get latest run
    run_info=$(get_latest_run)
    
    if [ -z "$run_info" ] || [ "$run_info" = "[]" ]; then
        echo -e "${YELLOW}$(date '+%H:%M:%S')${NC} - No runs found, waiting ${BASE_SLEEP}s... ($((retry_count+1))/$MAX_RETRIES)"
        sleep $BASE_SLEEP
        retry_count=$((retry_count+1))
        continue
    fi
    
    # Parse status
    run_status=$(parse_run_status "$run_info")
    run_url=$(parse_run_url "$run_info")
    run_branch=$(echo "$run_info" | python3 -c "
import sys, json
try:
    data = json.load(sys.stdin)
    if data and len(data) > 0:
        print(data[0].get('headBranch', 'unknown'))
except:
    print('unknown')
" 2>/dev/null)
    
    # Display status
    if echo "$run_status" | grep -q "^completed\."; then
        conclusion=$(echo "$run_status" | cut -d'.' -f2)
        
        if [ "$conclusion" = "success" ]; then
            echo -e "${GREEN}✅ $(date '+%H:%M:%S')${NC} - Workflow COMPLETED SUCCESSFULLY"
            echo "   Run on branch: $run_branch"
            if [ -n "$run_url" ]; then
                echo "   View: $run_url"
            fi
            
            # Download and install APK
            download_artifact "$run_url" "${run_info}"
            
            # Wait before checking for new runs
            echo -e "${YELLOW}$(date '+%H:%M:%S')${NC} - Waiting 60s before checking for new runs..."
            sleep 60
            retry_count=0  # Reset on success
            continue
            
        elif [ "$conclusion" = "failure" ]; then
            echo -e "${RED}❌ $(date '+%H:%M:%S')${NC} - Workflow FAILED"
            echo "   Run on branch: $run_branch"
            if [ -n "$run_url" ]; then
                echo "   View details: $run_url"
            fi
            
            retry_count=$((retry_count+1))
            echo -e "${YELLOW}$(date '+%H:%M:%S')${NC} - Retry $retry_count/$MAX_RETRIES in 30s..."
            sleep 30
            continue
            
        else
            echo -e "${YELLOW}⚠️ $(date '+%H:%M:%S')${NC} - Workflow completed with conclusion: $conclusion"
            retry_count=$((retry_count+1))
            sleep 20
            continue
        fi
        
    elif echo "$run_status" | grep -q "^in_progress\|^queued"; then
        echo -e "${YELLOW}⏱️ $(date '+%H:%M:%S')${NC} - Workflow RUNNING on branch: $run_branch"
        echo "   Status: $run_status"
        sleep 20
        
    else
        echo -e "${YELLOW}$(date '+%H:%M:%S')${NC} - Unknown status: $run_status"
        retry_count=$((retry_count+1))
        sleep 15
    fi
done

echo ""
echo "============================================================"
echo "Dastyab APK Monitor: Maximum retries ($MAX_RETRIES) reached"
echo "Exiting monitor script."
echo "============================================================"
echo ""
echo "Recommendations:"
echo "1. Check GitHub Actions manually: https://github.com/${REPO_OWNER}/${REPO_NAME}/actions"
echo "2. Verify network access to dl.google.com and services.gradle.org"
echo "3. Review workflow logs for specific error details"
echo "4. Run locally: cd android && ./gradlew :app:assembleDebug --no-daemon"
