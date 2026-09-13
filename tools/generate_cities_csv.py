"""
產生 app/src/main/res/raw/cities_50k.csv 用的離線腳本。

此腳本需要網路存取，請在你自己的電腦上執行（不要在建置機器/CI 沙盒跑）：

步驟：
1. 前往 https://download.geonames.org/export/dump/cities15000.zip 下載並解壓縮，
   取得 cities15000.txt（GeoNames 公開資料集，人口 >= 15,000 的城市，含座標）。
2. 把 cities15000.txt 放在跟本腳本同一個資料夾。
3. 執行： python generate_cities_csv.py
4. 產生的 cities_50k.csv 複製覆蓋到
   app/src/main/res/raw/cities_50k.csv
   （此 zip 內已附上一份 34 筆的示範版本，方便你先編譯測試整個流程，
    正式使用請務必用這支腳本產生的完整版本覆蓋掉。）

輸出格式（無 header）：name,lat,lon,population
"""

import csv

MIN_POP = 50_000
SRC_FILE = "cities15000.txt"
OUT_FILE = "cities_50k.csv"


def main():
    out_rows = []
    with open(SRC_FILE, encoding="utf-8") as f:
        reader = csv.reader(f, delimiter="\t")
        for row in reader:
            if len(row) < 15:
                continue
            name = row[1]           # 城市名稱（GeoNames 的 name 欄位）
            lat = row[4]
            lon = row[5]
            pop_raw = row[14]
            pop = int(pop_raw) if pop_raw.isdigit() else 0
            if pop >= MIN_POP:
                out_rows.append((name, lat, lon, pop))

    with open(OUT_FILE, "w", encoding="utf-8", newline="") as f:
        writer = csv.writer(f)
        for row in out_rows:
            writer.writerow(row)

    print(f"共寫入 {len(out_rows)} 個城市（人口 >= {MIN_POP}）到 {OUT_FILE}")


if __name__ == "__main__":
    main()
