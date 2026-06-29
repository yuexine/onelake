"""onelake SDK setup.py —— editable install: pip install -e ."""
from setuptools import setup, find_packages

setup(
    name="onelake",
    version="0.1.0",
    description="OneLake Notebook SDK：dataset() / publish()",
    packages=find_packages(),
    install_requires=[
        "pandas>=2.0",
        "trino>=0.330",
        "requests>=2.30",
    ],
    extras_require={
        "spark": ["pyspark>=3.4"],
    },
    python_requires=">=3.9",
)
