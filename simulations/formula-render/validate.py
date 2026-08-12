"""Check the zigzag hypothesis: reconstructed extents vs the bounding box the row stores."""

import sqlite3
import statistics

from inkdecode import decode_points

PAGE: str = "019fedda-2d27-7c7d-b796-86df1fa6aaeb"


def main() -> None:
    connection = sqlite3.connect("live.db")
    rows = connection.execute(
        "select id, sizeDp, minX, minY, maxX, maxY, points from ink_strokes "
        "where pageId = ? and deletedAt is null order by seq",
        (PAGE,),
    ).fetchall()
    connection.close()
    print("strokes:", len(rows))

    for zigzag in (True, False):
        errors: list[float] = []
        for _, size, min_x, min_y, max_x, max_y, blob in rows:
            runs = decode_points(blob, zigzag=zigzag)
            xs, ys = runs[1], runs[2]
            half = size / 2.0
            errors.extend(
                [
                    abs(min(xs) - half - min_x),
                    abs(min(ys) - half - min_y),
                    abs(max(xs) + half - max_x),
                    abs(max(ys) + half - max_y),
                ]
            )
        print(
            f"zigzag={zigzag}: median error {statistics.median(errors):.4f} dp, "
            f"max {max(errors):.4f} dp"
        )


main()
