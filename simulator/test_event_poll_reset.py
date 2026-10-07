"""An old UI test's pending poll must not own the next synthetic camera state."""

import asyncio
import unittest

from main import canon_poll_event, publish_event, reset_test_state, state


class EventPollResetTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self) -> None:
        await reset_test_state()
        self.polls: list[asyncio.Task] = []

    async def asyncTearDown(self) -> None:
        for poll in self.polls:
            poll.cancel()
        await asyncio.gather(*self.polls, return_exceptions=True)
        await reset_test_state()

    async def start_pending_poll(self) -> asyncio.Task:
        poll = asyncio.create_task(canon_poll_event(timeout="long", continue_mode=None))
        self.polls.append(poll)
        # Run the handler through its first suspension, after it registers ownership.
        await asyncio.sleep(0)
        self.assertFalse(poll.done())
        return poll

    async def test_retired_poll_cleanup_cannot_decrement_the_new_active_poll(self) -> None:
        old = await self.start_pending_poll()
        self.assertEqual(state["canonical_event_active_requests"], 1)
        await reset_test_state()
        current = await self.start_pending_poll()
        self.assertEqual(state["canonical_event_active_requests"], 1)

        old.cancel()
        with self.assertRaises(asyncio.CancelledError):
            await old

        self.assertFalse(current.done())
        self.assertEqual(state["canonical_event_poll_count"], 1)
        self.assertEqual(state["canonical_event_active_requests"], 1)

    async def test_retired_poll_cannot_consume_a_new_states_event(self) -> None:
        old = await self.start_pending_poll()
        await reset_test_state()
        publish_event("shootingsettings")

        retired_result = await asyncio.wait_for(old, timeout=1)
        self.assertEqual(retired_result, {})
        self.assertEqual(state["canonical_event_delivery_count"], 0)
        self.assertEqual(state["canonical_event_cursor"], 0)

        current_result = await canon_poll_event(timeout="immediately", continue_mode=None)
        self.assertEqual(current_result, {"shootingsettings": {}})
        self.assertEqual(state["canonical_event_delivery_count"], 1)
        self.assertEqual(state["canonical_event_active_requests"], 0)

    async def test_current_poll_still_delivers_events_and_releases_its_count(self) -> None:
        current = await self.start_pending_poll()
        publish_event("contents")

        self.assertEqual(await asyncio.wait_for(current, timeout=1), {"contents": {}})
        self.assertEqual(state["canonical_event_poll_count"], 1)
        self.assertEqual(state["canonical_event_delivery_count"], 1)
        self.assertEqual(state["canonical_event_active_requests"], 0)
