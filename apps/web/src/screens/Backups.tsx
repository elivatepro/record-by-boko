// W8 Backups and clean-up: where each recording's copies are (server, Drive, recorder), from the
// recordings' backup and clean-up states. The web can't see the phone's own copy.
import { Link } from "@tanstack/react-router";

import { useAllRecordings, useMe } from "../api/queries";
import { ErrorState, Loading, StatusWord } from "../components/common";
import { Icon } from "../components/Icon";
import { useNow } from "../lib/hooks";
import { driveStatus, recorderStatus, serverStatus } from "../lib/status";
import { duration, localToday, wallDay, whenLabel } from "../lib/time";

export function Backups() {
  const recordings = useAllRecordings();
  const me = useMe();
  const today = localToday(useNow());
  const list = [...(recordings.data ?? [])].sort((a, b) => b.started_at.localeCompare(a.started_at));
  const backedUp = list.filter((r) => r.backup_state === "backed_up").length;
  const waiting = list.filter((r) => ["waiting", "uploading", "checking"].includes(r.backup_state)).length;
  const failed = list.filter((r) => r.backup_state === "failed").length;
  const drive = me.data?.drive_connected;

  return (
    <div className="page backups">
      <Link to="/settings" className="breadcrumb">
        <Icon name="chevronLeft" size={16} />
        Settings
      </Link>
      <h1 className="page-title page-title--large">Backups and clean-up</h1>

      <div className="backup-tiles">
        <div className="backup-tile backup-tile--drive">
          <span className="round-icon" aria-hidden="true">
            <Icon name="cloud" />
          </span>
          <div>
            <p className="backup-tile__title">
              Google Drive · {drive === undefined ? "checking…" : drive ? "connected" : "not connected"}
            </p>
            <p className="backup-tile__sub">
              {drive === false
                ? "Sign out and in again to grant Drive access. Nothing is backed up until then."
                : "Audio, transcripts and notes, one folder per day"}
            </p>
          </div>
        </div>
        <div className="backup-tile">
          <span className="backup-tile__n tabular">{backedUp}</span>
          <span>backed up and checked</span>
        </div>
        <div className="backup-tile">
          <span className="backup-tile__n tabular">{waiting}</span>
          <span>waiting</span>
        </div>
        <div className={`backup-tile${failed > 0 ? " backup-tile--alert" : ""}`}>
          <span className="backup-tile__n tabular">{failed}</span>
          <span>{failed > 0 ? "failed · retried by itself" : "failed"}</span>
        </div>
      </div>

      {recordings.isPending ? (
        <Loading />
      ) : recordings.isError ? (
        <ErrorState title="Backups didn’t load" error={recordings.error} onRetry={() => void recordings.refetch()} />
      ) : list.length === 0 ? (
        <p className="muted">No recordings yet. They appear here once your phone has uploaded them.</p>
      ) : (
        <table className="copies">
          <caption className="sr-only">Where each recording’s copies are</caption>
          <thead>
            <tr>
              <th scope="col">Recording</th>
              <th scope="col">Server</th>
              <th scope="col">Drive</th>
              <th scope="col">Recorder</th>
            </tr>
          </thead>
          <tbody>
            {list.map((r) => {
              const drive = driveStatus(r);
              return (
                <tr key={r.id} className={drive.tone === "alert" ? "copies__row--alert" : undefined}>
                  <th scope="row">
                    <Link to="/day/$day" params={{ day: wallDay(r.started_at, r.zone_offset_seconds) }} className="copies__title">
                      Recording · {whenLabel(r.started_at, r.zone_offset_seconds, today)}
                    </Link>
                    <span className="copies__sub">{r.duration_ms === null ? "Length unknown" : duration(r.duration_ms)}</span>
                  </th>
                  <td>
                    <StatusWord status={serverStatus(r)} />
                  </td>
                  <td>
                    <StatusWord status={drive} cloud />
                    {drive.detail && <span className="copies__detail">{drive.detail}</span>}
                  </td>
                  <td>
                    <StatusWord status={recorderStatus(r)} />
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
      )}

      <p className="backups__foot">
        A recording is cleared from the recorder only when the phone, server and Drive copies all match. Your phone keeps
        everything until you delete it there.
      </p>
    </div>
  );
}
