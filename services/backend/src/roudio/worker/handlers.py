"""Every job kind the worker runs. Kept apart from the loop so handlers can import it freely."""

from roudio.worker.loop import HANDLERS, Handler


def all_handlers() -> dict[str, Handler]:
    from roudio.pipeline import digest, process

    return {**HANDLERS, process.PROCESS: process.process_recording, **digest.HANDLERS}
