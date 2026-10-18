"""Source-scope regression, not a Compose/device runtime test.

SettingsPage's content is LazyListScope, not a composable host scope. Host
consumers and readiness effects must run inside an item composed by that page.
Run: python3 -m unittest discover -s scripts/tests -v
"""
import re
import unittest
from pathlib import Path


SCREEN = (Path(__file__).resolve().parents[2] / "app/src/main/kotlin/com/"
          "macedonga/visor/features/ConversationFocusScreen.kt")


def blocks_at(source, offset):
    """Return enclosing brace offsets, ignoring comments and quoted literals."""
    token = re.compile(r'//[^\n]*|/\*.*?\*/|"(?:\\.|[^"\\])*"|[{}]', re.S)
    stack = []
    for match in token.finditer(source, 0, offset):
        if match.group() == "{":
            stack.append(match.start())
        elif match.group() == "}":
            stack.pop()
    return stack


def block_header(source, brace):
    # Calls here have one-line headers; do not include prior statement text.
    return source[source.rfind("\n", 0, brace) + 1:brace].strip()


class ConversationFocusHostScopeTest(unittest.TestCase):
    def test_host_reads_and_readiness_effect_are_composed_inside_page_item(self):
        source = SCREEN.read_text()
        # Limit to the screen entry point; FocusNow is already an item child.
        start = source.index("fun ConversationFocusScreen(")
        end = source.index("/** Where conversation focus stands", start)
        screen = source[start:end]
        consumers = list(re.finditer(r"LocalHost\.current|LaunchedEffect\(ready\)", screen))
        self.assertGreaterEqual(len(consumers), 2, "host read and readiness effect must remain")
        for consumer in consumers:
            with self.subTest(consumer=consumer.group(), line=screen.count("\n", 0, consumer.start()) + 1):
                headers = [block_header(screen, brace) for brace in blocks_at(screen, consumer.start())]
                page = next((i for i, header in enumerate(headers) if header.startswith("SettingsPage(")), None)
                self.assertIsNotNone(page, "host consumer executes before SettingsPage installs LocalHost")
                assert page is not None
                self.assertTrue(any(header.startswith("item(") for header in headers[page + 1:]),
                                "LazyListScope itself is not a composed item")

    def test_readiness_check_retains_existing_guards_and_key(self):
        source = SCREEN.read_text()
        effect = re.search(r"LaunchedEffect\(ready\)\s*\{([^}]+)}", source)
        self.assertIsNotNone(effect)
        assert effect is not None
        self.assertRegex(effect.group(1),
                         r"if\s*\(ready\s*&&\s*!state\.configRead\s*&&\s*!state\.busy\)\s*model\.features\.check\(\)")


if __name__ == "__main__":
    unittest.main()
