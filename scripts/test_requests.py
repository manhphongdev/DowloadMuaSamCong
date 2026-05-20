"""
Script test nhiều request đồng thời vào muasamcong.mpi.gov.vn
để kiểm tra anti-bot / rate limit.
"""
import requests
import time
import concurrent.futures
from datetime import datetime

BASE_URL = "https://muasamcong.mpi.gov.vn"
SEARCH_URL = "https://muasamcong.mpi.gov.vn/web/guest/contractor-selection?render=search"

HEADERS = {
    "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36",
    "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
    "Accept-Language": "vi-VN,vi;q=0.9,en;q=0.8",
    "Connection": "keep-alive",
}

URLS_TO_TEST = [
    BASE_URL,
    SEARCH_URL,
    f"{BASE_URL}/web/guest/news",
    f"{BASE_URL}/web/guest/contractor-selection",
]

def make_request(url, request_id):
    """Gửi 1 request và trả về kết quả."""
    start = time.time()
    try:
        resp = requests.get(url, headers=HEADERS, timeout=15, allow_redirects=True)
        elapsed = time.time() - start
        return {
            "id": request_id,
            "url": url,
            "status": resp.status_code,
            "size": len(resp.content),
            "time": round(elapsed, 2),
            "blocked": is_blocked(resp),
            "redirect": resp.url if resp.url != url else None,
        }
    except requests.exceptions.Timeout:
        return {"id": request_id, "url": url, "status": "TIMEOUT", "time": round(time.time() - start, 2), "blocked": "timeout"}
    except requests.exceptions.ConnectionError as e:
        return {"id": request_id, "url": url, "status": "CONN_ERROR", "time": round(time.time() - start, 2), "blocked": str(e)[:80]}
    except Exception as e:
        return {"id": request_id, "url": url, "status": "ERROR", "time": round(time.time() - start, 2), "blocked": str(e)[:80]}

def is_blocked(resp):
    """Kiểm tra xem response có dấu hiệu bị block không."""
    content = resp.text.lower()
    indicators = [
        "captcha", "blocked", "rate limit", "too many requests",
        "access denied", "forbidden", "cloudflare", "challenge",
        "bot detection", "please verify", "unusual traffic",
        "request rejected", "waf", "firewall"
    ]
    found = [ind for ind in indicators if ind in content]
    if resp.status_code == 403:
        found.append("HTTP 403")
    if resp.status_code == 429:
        found.append("HTTP 429 (Rate Limited)")
    if resp.status_code == 503:
        found.append("HTTP 503")
    return found if found else None


def run_sequential_test(count=10):
    """Gửi request tuần tự, không delay."""
    print(f"\n{'='*60}")
    print(f"TEST 1: {count} request TUẦN TỰ (không delay)")
    print(f"{'='*60}")
    results = []
    for i in range(count):
        r = make_request(BASE_URL, i + 1)
        results.append(r)
        status_str = f"#{r['id']:02d} | {r['status']} | {r['time']}s | size={r.get('size', '?')}"
        if r['blocked']:
            status_str += f" | BLOCKED: {r['blocked']}"
        print(status_str)
    return results


def run_concurrent_test(count=20, max_workers=10):
    """Gửi request đồng thời."""
    print(f"\n{'='*60}")
    print(f"TEST 2: {count} request ĐỒNG THỜI (max {max_workers} workers)")
    print(f"{'='*60}")
    results = []
    with concurrent.futures.ThreadPoolExecutor(max_workers=max_workers) as executor:
        futures = {
            executor.submit(make_request, URLS_TO_TEST[i % len(URLS_TO_TEST)], i + 1): i
            for i in range(count)
        }
        for future in concurrent.futures.as_completed(futures):
            r = future.result()
            results.append(r)
            status_str = f"#{r['id']:02d} | {r['status']} | {r['time']}s | size={r.get('size', '?')}"
            if r['blocked']:
                status_str += f" | BLOCKED: {r['blocked']}"
            print(status_str)
    return results


def run_burst_test(bursts=3, per_burst=10, delay_between=2):
    """Gửi burst request với delay giữa các burst."""
    print(f"\n{'='*60}")
    print(f"TEST 3: {bursts} BURST x {per_burst} request (delay {delay_between}s giữa burst)")
    print(f"{'='*60}")
    all_results = []
    for burst_num in range(bursts):
        print(f"\n--- Burst {burst_num + 1}/{bursts} ---")
        with concurrent.futures.ThreadPoolExecutor(max_workers=per_burst) as executor:
            futures = {
                executor.submit(make_request, BASE_URL, burst_num * per_burst + i + 1): i
                for i in range(per_burst)
            }
            for future in concurrent.futures.as_completed(futures):
                r = future.result()
                all_results.append(r)
                status_str = f"#{r['id']:02d} | {r['status']} | {r['time']}s"
                if r['blocked']:
                    status_str += f" | BLOCKED: {r['blocked']}"
                print(status_str)
        if burst_num < bursts - 1:
            print(f"   ... waiting {delay_between}s ...")
            time.sleep(delay_between)
    return all_results


def summarize(results, test_name):
    """Tổng kết kết quả."""
    total = len(results)
    blocked = [r for r in results if r['blocked']]
    errors = [r for r in results if isinstance(r['status'], str)]  # TIMEOUT, ERROR, etc.
    success = [r for r in results if r.get('status') == 200 and not r['blocked']]
    times = [r['time'] for r in results if isinstance(r['time'], (int, float))]

    print(f"\n--- Tổng kết {test_name} ---")
    print(f"  Tổng request: {total}")
    print(f"  Thành công (200, không block): {len(success)}")
    print(f"  Bị block/captcha: {len(blocked)}")
    print(f"  Lỗi/timeout: {len(errors)}")
    if times:
        print(f"  Thời gian: min={min(times)}s, max={max(times)}s, avg={sum(times)/len(times):.2f}s")
    if blocked:
        print(f"  Chi tiết block:")
        for r in blocked[:5]:
            print(f"    #{r['id']} - {r['blocked']}")
    print()


if __name__ == "__main__":
    print(f"Bắt đầu test: {datetime.now().strftime('%Y-%m-%d %H:%M:%S')}")
    print(f"Target: {BASE_URL}")

    # Test 1: Tuần tự
    r1 = run_sequential_test(10)
    summarize(r1, "Tuần tự")

    time.sleep(3)

    # Test 2: Đồng thời
    r2 = run_concurrent_test(20, max_workers=10)
    summarize(r2, "Đồng thời")

    time.sleep(3)

    # Test 3: Burst
    r3 = run_burst_test(bursts=3, per_burst=10, delay_between=2)
    summarize(r3, "Burst")

    print(f"\nKết thúc: {datetime.now().strftime('%Y-%m-%d %H:%M:%S')}")
