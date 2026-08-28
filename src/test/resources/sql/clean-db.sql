-- PostgreSQL. Wipes all domain data between tests; CASCADE follows every FK so order is irrelevant.
TRUNCATE TABLE test, collection, users, authorities RESTART IDENTITY CASCADE;
