"""Download the worldwide places Bivouac needs from Overture Maps.

Overture's places theme (~80M places, CDLA-Permissive-2.0 / Apache-2.0 / CC0)
is queried remotely with DuckDB, so only the needed columns and rows are
transferred: confident, not-closed places in categories a traveler looks for.

Usage: python fetch_places.py [--release 2026-09-23.1] [--out data/places.parquet]
"""

import argparse
import time

import duckdb

# Top-level Overture taxonomy groups kept in full.
KEEP_TOP = [
    "food_and_drink", "health_care", "travel_and_transportation", "lodging", "shopping",
    "cultural_and_historic", "sports_and_recreation", "arts_and_entertainment",
    "community_and_government", "geographic_entities",
]
# From the large "services_and_business" group, only what travelers need.
KEEP_SERVICES = [
    "atm", "bank", "currency_exchange", "laundromat", "laundry_service", "post_office",
    "car_rental", "bicycle_rental", "gas_station", "electric_vehicle_charging_station",
    "public_toilet", "tourist_information_center", "travel_agency", "internet_cafe",
    "locksmith", "mobile_phone_store", "phone_repair",
]


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--release", default="2026-09-23.1")
    ap.add_argument("--out", default="data/places.parquet")
    ap.add_argument("--min-confidence", type=float, default=0.7)
    args = ap.parse_args()

    src = f"s3://overturemaps-us-west-2/release/{args.release}/theme=places/type=place/*.parquet"
    con = duckdb.connect()
    con.execute("INSTALL httpfs; LOAD httpfs; SET s3_region='us-west-2'; SET threads=4;")
    keep_top = ", ".join(f"'{c}'" for c in KEEP_TOP)
    keep_services = ", ".join(f"'{c}'" for c in KEEP_SERVICES)
    t0 = time.time()
    con.execute(f"""
        COPY (
            SELECT
                names.primary                                 AS name,
                names.common['en']                            AS name_en,
                brand.names.primary                           AS brand,
                taxonomy.primary                              AS category,
                taxonomy.alternates                           AS alt_categories,
                round(confidence, 2)                          AS confidence,
                (bbox.ymin + bbox.ymax) / 2                   AS lat,
                (bbox.xmin + bbox.xmax) / 2                   AS lon,
                addresses[1].freeform                         AS street,
                addresses[1].locality                         AS locality,
                addresses[1].region                           AS region,
                addresses[1].country                          AS country,
                phones[1]                                     AS phone
            FROM read_parquet('{src}')
            WHERE confidence >= {args.min_confidence}
              AND coalesce(operating_status, 'open') <> 'permanently_closed'
              AND names.primary IS NOT NULL
              AND taxonomy.primary IS NOT NULL
              AND (taxonomy.hierarchy[1] IN ({keep_top})
                   OR taxonomy.primary IN ({keep_services}))
        ) TO '{args.out}' (FORMAT parquet, COMPRESSION zstd, ROW_GROUP_SIZE 200000)
    """)
    n = con.sql(f"SELECT count(*) FROM read_parquet('{args.out}')").fetchone()[0]
    print(f"{n:,} places in {(time.time() - t0) / 60:.1f} min -> {args.out}", flush=True)


if __name__ == "__main__":
    main()
