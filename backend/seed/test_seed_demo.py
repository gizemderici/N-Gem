import unittest

from seed import seed_demo


class DemoSeedDefinitionTest(unittest.TestCase):
    def test_expected_demo_size(self):
        self.assertEqual(8, len(seed_demo.USERS))
        self.assertEqual(30, len(seed_demo.POSTS))

    def test_accounts_and_posts_are_unique(self):
        usernames = [user.username for user in seed_demo.USERS]
        emails = [user.email for user in seed_demo.USERS]
        post_keys = [(post["username"], post["text"]) for post in seed_demo.POSTS]
        self.assertEqual(len(usernames), len(set(usernames)))
        self.assertEqual(len(emails), len(set(emails)))
        self.assertEqual(len(post_keys), len(set(post_keys)))

    def test_all_references_point_to_demo_users(self):
        usernames = {user.username for user in seed_demo.USERS}
        self.assertTrue(all(post["username"] in usernames for post in seed_demo.POSTS))
        self.assertTrue(all(owner in usernames for owner in seed_demo.FOLLOWS))
        self.assertTrue(all(target in usernames for targets in seed_demo.FOLLOWS.values() for target in targets))
        self.assertTrue(all(author in usernames for _, author, _ in seed_demo.COMMENTS))
        self.assertTrue(all(first in usernames and second in usernames for first, second, _ in seed_demo.CONVERSATIONS))

    def test_assets_exist(self):
        assets = {post["asset"] for post in seed_demo.POSTS if post.get("asset")}
        assets.update(user.avatar for user in seed_demo.USERS)
        assets.update(asset for _, _, asset in seed_demo.STORIES)
        self.assertTrue(all((seed_demo.ASSETS_DIR / asset).is_file() for asset in assets))

    def test_social_specs_are_safe(self):
        self.assertTrue(all(owner not in targets for owner, targets in seed_demo.FOLLOWS.items()))
        self.assertTrue(all(0 <= post_index < len(seed_demo.POSTS) for post_index, _, _ in seed_demo.COMMENTS))

    def test_blocked_pair_never_interacts_with_each_others_posts(self):
        class RecordingClient:
            def __init__(self):
                self.paths = []

            def request(self, method, path, payload=None, token=None):
                self.paths.append(path)
                return {}

        client = RecordingClient()
        tokens = {"demo_onur": "onur-token", "demo_zeynep": "zeynep-token"}
        posts = [
            {"id": "onur-post", "author": {"username": "demo_onur"}},
            {"id": "zeynep-post", "author": {"username": "demo_zeynep"}},
        ]
        seed_demo.add_social_proof(client, tokens, posts)
        self.assertEqual([], client.paths)


if __name__ == "__main__":
    unittest.main()
